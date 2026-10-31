/*
*      _______                       _____   _____ _____  
*     |__   __|                     |  __ \ / ____|  __ \ 
*        | | __ _ _ __ ___  ___  ___| |  | | (___ | |__) |
*        | |/ _` | '__/ __|/ _ \/ __| |  | |\___ \|  ___/ 
*        | | (_| | |  \__ \ (_) \__ \ |__| |____) | |     
*        |_|\__,_|_|  |___/\___/|___/_____/|_____/|_|     
*                                                         
* -------------------------------------------------------------
*
* TarsosDSP is developed by Joren Six at IPEM, University Ghent
*  
* -------------------------------------------------------------
*
*  Info: http://0110.be/tag/TarsosDSP
*  Github: https://github.com/JorenSix/TarsosDSP
*  Releases: http://0110.be/releases/TarsosDSP/
*  
*  TarsosDSP includes modified source code by various authors,
*  for credits and info, see README.
* 
*/

/*
	Copyright (C) 2001, 2006 by Simon Dixon

	This program is free software; you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation; either version 2 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License along
	with this program (the file gpl.txt); if not, download it from
	http://www.gnu.org/licenses/gpl.txt or write to the
	Free Software Foundation, Inc.,
	51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/

/*  Modified for Filet, 2026-10-30: the binary file and MIDI reading and writing are removed.
    Filet keeps an event list in memory for one measurement and never serialises it, and
    javax.sound.midi does not exist on Android. Nothing else in this file is changed.
*/

package be.tarsos.dsp.beatroot;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;



// Adapted from eventList::readMatchFile in beatroot/src/eventMidi.cpp

// Reads in a Prolog score+performance (.match) file; returns it as an eventList
// Lines in the match file can be of the form:
//		hammer_bounce-PlayedNote.
//		info(Attribute, Value).
//		insertion-PlayedNote.
//		ornament(Anchor)-PlayedNote.
//		ScoreNote-deletion.
//		ScoreNote-PlayedNote.
//		ScoreNote-trailing_score_note.
//		trailing_played_note-PlayedNote.
//		trill(Anchor)-PlayedNote.
// where ScoreNote is of the form
//		snote(Anchor,[NoteName,Modifier],Octave,Bar:Beat,Offset,Duration,
//				BeatNumber,DurationInBeats,ScoreAttributesList)
//		e.g. snote(n1,[b,b],5,1:1,0,3/16,0,0.75,[s])
// and PlayedNote is of the form
//		note(Number,[NoteName,Modifier],Octave,Onset,Offset,AdjOffset,Velocity)
//		e.g. note(1,[a,#],5,5054,6362,6768,53)

class WormFileParseException extends RuntimeException {

	static final long serialVersionUID = 0;
	public WormFileParseException(String s) {
		super(s);
	} // constructor

} // class WormFileParseException

class MatchFileParseException extends RuntimeException {

	static final long serialVersionUID = 0;
	public MatchFileParseException(String s) {
		super(s);
	} // constructor

} // class MatchFileParseException

class BTFileParseException extends RuntimeException {

	static final long serialVersionUID = 0;
	public BTFileParseException(String s) {
		super(s);
	} // constructor

} // class BTFileParseException


// Process the strings which label extra features of notes in match files.
// We assume no more than 32 distinct labels in a file.
class Flags {

	String[] labels = new String[32];
	int size = 0;
	
	int getFlag(String s) {
		if ((s == null) || s.equals(""))
			return 0;
		//int val = 1;
		for (int i = 0; i < size; i++)
			if (s.equals(labels[i]))
				return 1 << i;
		if (size == 32)	{
			System.err.println("Overflow: Too many flags: " + s);
			size--;
		}
		labels[size] = s;
		return 1 << size++;
	} // getFlag()

	String getLabel(int i) {
		if (i >= size)
			return "ERROR: Unknown flag";
		return labels[i];
	} // getLabel()

} // class Flags

/**
 *  A score/match/midi file is represented as an EventList object,
 *  which contains pointers to the head and tail links, and some
 *   class-wide parameters. Parameters are class-wide, as it is
 *   assumed that the Worm has only one input file at a time.
 */
public class EventList {

	public LinkedList<Event> l;

	private static boolean timingCorrection = false;
	private static double timingDisplacement = 0;
	private static int clockUnits = 480;
	private static int clockRate = 500000;
	private static double metricalLevel = 0;
	private static final double UNKNOWN = Double.NaN;
	private static boolean noMelody = false;
	private static boolean onlyMelody = false;
	private static Flags flags = new Flags();

	public EventList() {
		l = new LinkedList<Event>();
	} // constructor

	public EventList(EventList e) {
		this();
		ListIterator<Event> it = e.listIterator();
		while (it.hasNext())
			add(it.next());
	} // constructor

	public EventList(Event[] e) {
		this();
		for (int i=0; i < e.length; i++)
			add(e[i]);
	} // constructor

	public void add(Event e) {
		l.add(e);
	} // add()

	public void add(EventList ev) {
		l.addAll(ev.l);
	} // add()

	public void insert(Event newEvent, boolean uniqueTimes) {
		ListIterator<Event> li = l.listIterator();
		while (li.hasNext()) {
			int sgn = newEvent.compareTo(li.next());
			if (sgn < 0) {
				li.previous();
				break;
			} else if (uniqueTimes && (sgn == 0)) {
				li.remove();
				break;
			}
		}
		li.add(newEvent);
	} // insert()

	public ListIterator<Event> listIterator() {
		return l.listIterator();
	} // listIterator()

	public Iterator<Event> iterator() {
		return l.iterator();
	} // iterator()

	public int size() {
		return l.size();
	} // size()

	public Event[] toArray() {
		return toArray(0);
	} // toArray()

	public double[] toOnsetArray() {
		double[] d = new double[l.size()];
		int i = 0;
		for (Iterator<Event> it = l.iterator(); it.hasNext(); i++)
			d[i] = it.next().keyDown;
		return d;
	} // toOnsetArray()

	public Event[] toArray(int match) {
		int count = 0;
		for (Event e : l)
			if ((match == 0) || (e.midiCommand == match))
				count++;
		Event[] a = new Event[count];
		int i = 0;
		for (Event e : l)
			if ((match == 0) || (e.midiCommand == match))
				a[i++] = e;
		return a;
	} // toArray()

	public void print() {
		for (Iterator<Event> i = l.iterator(); i.hasNext(); )
			i.next().print(flags);
	} // print()

	public static void setTimingCorrection(double corr) {
		timingCorrection = corr >= 0;
		timingDisplacement = corr;
	} // setTimingCorrection()

	
	
	
	
} // class EventList
