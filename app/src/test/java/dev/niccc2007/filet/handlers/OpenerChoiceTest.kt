package dev.niccc2007.filet.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The two bugs Nic hit, as assertions.
 *
 * Both were decisions buried inside an `onClick`, which is why neither had a test and why
 * both shipped. Moving the decision out is most of the fix.
 */
class OpenerChoiceTest {

    @Test fun another_app_always_opens_the_app_list() {
        // The reported bug: a .pdf already routed externally. Tapping "Another app" - the row
        // it is already on - has to offer the list, not sit there as a no-op.
        assertEquals(
            OpenerChoice.PickApp,
            openerChoice(picked = HandlerId.EXTERNAL, builtIn = HandlerId.TEXT),
        )
    }

    @Test fun another_app_opens_the_list_even_when_it_is_the_built_in() {
        // The nastier half. For a .docx the BUILT-IN is already EXTERNAL, so a naive
        // "picked == builtIn -> clear" test would swallow the tap on exactly the types where
        // choosing an app matters most.
        assertEquals(
            OpenerChoice.PickApp,
            openerChoice(picked = HandlerId.EXTERNAL, builtIn = HandlerId.EXTERNAL),
        )
    }

    @Test fun picking_filets_own_default_clears_the_override() {
        assertEquals(
            OpenerChoice.ClearOverride,
            openerChoice(picked = HandlerId.MEDIA, builtIn = HandlerId.MEDIA),
        )
    }

    @Test fun picking_another_filet_handler_routes_there() {
        assertEquals(
            OpenerChoice.SetHandler(HandlerId.HEX),
            openerChoice(picked = HandlerId.HEX, builtIn = HandlerId.TEXT),
        )
    }

    @Test fun every_handler_resolves_to_something() {
        // No silent hole. A new HandlerId added later must still produce a decision.
        for (id in HandlerId.entries) {
            for (builtIn in HandlerId.entries) {
                openerChoice(id, builtIn)
            }
        }
    }

    @Test fun a_chooser_does_not_remember_unless_told() {
        assertFalse(
            "opening one file in one app once must not write a permanent default",
            remembersByDefault(),
        )
    }
}
