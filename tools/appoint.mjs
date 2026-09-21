/**
 * Appointing things for removal in the storage tool, when the thing is a tree.
 *
 * The screen has to answer a question a flat checkbox list cannot: you appoint a folder, walk
 * into it, untick one file, and come back out. What does the folder's box look like now, what
 * does the tray say, and what actually gets removed?
 *
 * ## The model
 *
 * Selection is NOT a set of ticked files. It is two sets of *decisions*:
 *
 *   appointed - nodes the user pointed at and said "this"
 *   excluded  - nodes the user pointed at and said "not this", inside something appointed
 *
 * Everything else is derived. This matters because it preserves intent: "I appointed
 * Download" survives walking in and out of it, survives the folder gaining a file after the
 * scan, and stays one row in the tray instead of exploding into 91. A flat set of leaves
 * would lose all three - it cannot tell "I picked these 91 files" from "I picked the folder",
 * and the tray has to show something different in each case.
 *
 * Resolution is nearest-ancestor-wins: walking from a node up to the root, the first
 * decision found is the one that applies. So an exclusion inside an appointment beats it, and
 * an appointment inside that exclusion beats THAT, to any depth.
 *
 * ## The rules, stated so they can be argued with
 *
 * 1. A folder's box is ALL when every leaf under it resolves on, NONE when none does, and
 *    PARTIAL in between. It is never stored - always computed - so it cannot go stale.
 * 2. Ticking a folder that is NONE or PARTIAL turns the whole thing on. Ticking one that is
 *    ALL turns it off. Partial goes to full rather than to empty: the half-state came from
 *    subtracting, so the gesture that clears it should be the one that adds.
 * 3. A decision wipes every decision beneath it. Once you say "all of Download", the memory
 *    of which three files you had ticked inside it is not information any more, and keeping
 *    it would make a later untick behave in a way nobody can predict.
 * 4. An appointment that ends up covering nothing is dropped. 0 of 120 is not an appointment,
 *    and a tray row that removes nothing is a row that gets pressed by mistake.
 * 5. Fully-appointed siblings collapse into their parent. Ticking every child one at a time
 *    should leave the same state as ticking the parent once, or the tray says something
 *    different depending on how you got there.
 *
 * ## Counting
 *
 * A folder carries its own leaf count, because the tool must work on folders it has not
 * walked into. `count` is authoritative for a folder with no loaded children, and the sum of
 * the children when they are loaded. Sizes follow the same rule.
 *
 * Every function here is pure: same inputs, same output, no clock, no DOM. The mock embeds
 * this exact block and check-appoint.mjs proves the two have not drifted.
 */

/* --8<-- appoint core --8<-- */
/** A node is { id, name, dir, size, count, children? }. count is leaves in the subtree. */

/** Leaves under a node. Loaded children win over the stored count; a leaf is one. */
export function leafTotal(node) {
  if (!node.dir) return 1;
  if (!node.children || node.children.length === 0) return node.count || 0;
  return node.children.reduce((a, c) => a + leafTotal(c), 0);
}

/** Bytes under a node, by the same rule. */
export function sizeTotal(node) {
  if (!node.dir) return node.size || 0;
  if (!node.children || node.children.length === 0) return node.size || 0;
  return node.children.reduce((a, c) => a + sizeTotal(c), 0);
}

/** An empty selection. Arrays, not Sets, so order survives a round trip through storage. */
export function emptySel() {
  return { appointed: [], excluded: [] };
}

/** Root-to-node trail, or null. */
export function findPath(root, id, trail = []) {
  const here = [...trail, root];
  if (root.id === id) return here;
  for (const c of root.children || []) {
    const hit = findPath(c, id, here);
    if (hit) return hit;
  }
  return null;
}

/** Every id in a subtree, the node itself included. */
export function subtreeIds(node, out = []) {
  out.push(node.id);
  for (const c of node.children || []) subtreeIds(c, out);
  return out;
}

/**
 * Does this node resolve on, given the decisions above it?
 *
 * Nearest decision wins, which is the whole of the nesting behaviour.
 */
export function resolvedOn(path, sel) {
  for (let i = path.length - 1; i >= 0; i--) {
    const id = path[i].id;
    if (sel.excluded.includes(id)) return false;
    if (sel.appointed.includes(id)) return true;
  }
  return false;
}

/** Selected leaves under a node, given whether the node itself is inherited on. */
export function selectedUnder(node, inheritedOn, sel) {
  let on = inheritedOn;
  if (sel.appointed.includes(node.id)) on = true;
  if (sel.excluded.includes(node.id)) on = false;
  if (!node.dir) return on ? 1 : 0;
  if (!node.children || node.children.length === 0) return on ? (node.count || 0) : 0;
  return node.children.reduce((a, c) => a + selectedUnder(c, on, sel), 0);
}

/** Selected bytes under a node, same shape. */
export function selectedBytes(node, inheritedOn, sel) {
  let on = inheritedOn;
  if (sel.appointed.includes(node.id)) on = true;
  if (sel.excluded.includes(node.id)) on = false;
  if (!node.dir) return on ? (node.size || 0) : 0;
  if (!node.children || node.children.length === 0) return on ? (node.size || 0) : 0;
  return node.children.reduce((a, c) => a + selectedBytes(c, on, sel), 0);
}

/** "none" | "partial" | "all". Always computed, never stored. */
export function nodeState(root, id, sel) {
  const path = findPath(root, id);
  if (!path) return "none";
  const node = path[path.length - 1];
  const inherited = resolvedOn(path.slice(0, -1), sel);
  const picked = selectedUnder(node, inherited, sel);
  const total = leafTotal(node);
  if (picked === 0) return "none";
  if (total > 0 && picked >= total) return "all";
  return "partial";
}

/** What the tick under a node's row should read: picked, total, and the state. */
export function tallyFor(root, id, sel) {
  const path = findPath(root, id);
  if (!path) return { picked: 0, total: 0, state: "none", bytes: 0 };
  const node = path[path.length - 1];
  const inherited = resolvedOn(path.slice(0, -1), sel);
  return {
    picked: selectedUnder(node, inherited, sel),
    total: leafTotal(node),
    bytes: selectedBytes(node, inherited, sel),
    state: nodeState(root, id, sel),
  };
}

/**
 * Tick or untick one node.
 *
 * Rule 2 lives here: ALL goes off, NONE and PARTIAL both go on. Rule 3 lives here too - the
 * decisions beneath the node are wiped before the new one is recorded.
 */
export function toggle(root, id, sel) {
  const path = findPath(root, id);
  if (!path) return sel;
  const node = path[path.length - 1];
  const inherited = resolvedOn(path.slice(0, -1), sel);
  const state = nodeState(root, id, sel);
  const under = subtreeIds(node).filter((x) => x !== id);

  const next = {
    appointed: sel.appointed.filter((x) => x !== id && !under.includes(x)),
    excluded: sel.excluded.filter((x) => x !== id && !under.includes(x)),
  };

  if (state === "all") {
    // Off. An ancestor may still be covering it, in which case the way to say no is an
    // exclusion rather than the absence of an appointment.
    if (inherited) next.excluded.push(id);
  } else {
    // On. If an ancestor already covers it, the exclusion we just dropped was the only thing
    // keeping it off, so adding an appointment as well would be a second way to say the same
    // thing - and rule 5 would have to undo it.
    if (!inherited) next.appointed.push(id);
  }
  return normalise(root, next);
}

/** Drop every decision that no longer changes anything, then collapse full sets upward. */
export function normalise(root, sel) {
  let cur = { appointed: [...sel.appointed], excluded: [...sel.excluded] };

  // Rule 4: an appointment covering nothing is not an appointment.
  cur.appointed = cur.appointed.filter((id) => {
    const path = findPath(root, id);
    if (!path) return false;
    const node = path[path.length - 1];
    return selectedUnder(node, resolvedOn(path.slice(0, -1), cur), cur) > 0;
  });

  // An exclusion with nothing above it to exclude from is noise.
  cur.excluded = cur.excluded.filter((id) => {
    const path = findPath(root, id);
    if (!path) return false;
    return resolvedOn(path.slice(0, -1), cur);
  });

  // Rule 5, bottom-up so a collapse can feed the one above it.
  let changed = true;
  while (changed) {
    changed = false;
    const dirs = [];
    (function walk(n) {
      for (const c of n.children || []) walk(c);
      if (n.dir && n.children && n.children.length) dirs.push(n);
    })(root);
    for (const dir of dirs) {
      const path = findPath(root, dir.id);
      if (resolvedOn(path, cur)) continue;
      const every = dir.children.every((c) => nodeState(root, c.id, cur) === "all");
      if (!every) continue;
      const under = subtreeIds(dir).filter((x) => x !== dir.id);
      cur = {
        appointed: [...cur.appointed.filter((x) => !under.includes(x)), dir.id],
        excluded: cur.excluded.filter((x) => !under.includes(x)),
      };
      changed = true;
      break;
    }
  }
  return cur;
}

/**
 * The tray: one row per appointment, never one per file.
 *
 * This is the half the flat model could not express. A folder you appointed is one row that
 * says how much of it is going, so unticking one file inside it reads as "99 of 100" rather
 * than as ninety-nine separate rows appearing.
 */
export function trayRows(root, sel) {
  // An appointment sitting under another appointment is a re-inclusion, not a second thing
  // being removed: you appointed Download, excluded old-installers, then put one APK back.
  // The APK is going because Download is going, so it belongs in Download's count and not in
  // a row of its own - otherwise the tray lists the same bytes twice, and a list of
  // individual files reappears one nested exclusion after being designed out.
  const nested = (id) => {
    const path = findPath(root, id);
    if (!path) return true;
    return path.slice(0, -1).some((a) => sel.appointed.includes(a.id));
  };
  return sel.appointed
    .filter((id) => !nested(id))
    .map((id) => {
      const path = findPath(root, id);
      if (!path) return null;
      const node = path[path.length - 1];
      const t = tallyFor(root, id, sel);
      return {
        id,
        name: node.name,
        dir: !!node.dir,
        picked: t.picked,
        total: t.total,
        bytes: t.bytes,
        partial: node.dir && t.picked < t.total,
      };
    })
    .filter(Boolean);
}

/** Everything appointed, totalled, for the tray header and the buttons' enabled state. */
export function trayTotals(root, sel) {
  const rows = trayRows(root, sel);
  return {
    rows: rows.length,
    files: rows.reduce((a, r) => a + r.picked, 0),
    bytes: rows.reduce((a, r) => a + r.bytes, 0),
  };
}
/* --8<-- end --8<-- */
