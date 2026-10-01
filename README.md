[README_en.txt](https://github.com/user-attachments/files/32917403/README_en.txt)
====================================================================
 Worldstitch MOD Manual  -  Beta 0.5.2
====================================================================
 (Japanese version: README.txt / Changelog: CHANGELOG_en.txt)
====================================================================

A singleplayer-only mod that lets you build Nether-portal-style
"gates," link them together, and warp:
- to another spot in the same save
- across the Overworld/Nether/End
- to a different save entirely (fast save-switching)

Useful if you want to explore a fresh world without giving up a base
you've already built.


--------------------------------------------------------------------
1. Obtaining
--------------------------------------------------------------------
Gate Frame (gate_frame_block) - 8 per craft
Surround an Ender Pearl (E) with Chiseled Deepslate (D) in a 3x3:
        D D D
        D E D
        D D D

Gate Linker (gate_linker)
    Writable Book + Ender Pearl (shapeless)

Note: the "gate portal" block that forms inside isn't obtainable as
an item; it appears automatically once a frame is built correctly.

Bonus chest: enabling it on world creation guarantees 16 Gate Frames
and 1 Gate Linker (doesn't affect existing loot; skipped if another
mod fully replaces the bonus chest loot table).


--------------------------------------------------------------------
2. Building a gate
--------------------------------------------------------------------
Same as a Nether portal:

  1. Build a hollow rectangular frame (inner size: min 2x3, max
     21x21; corners are optional)
  2. Once linked (section 3), the portal forms automatically inside
  3. Touch the portal to warp to its target

Right-click the portal with a dye to recolor it (the linked side's
color updates too).

Mobs and dropped items can also cross, but only for same-save links;
cross-save links are player-only, so other entities just pass through.


--------------------------------------------------------------------
3. Linking two gates
--------------------------------------------------------------------
  On the source gate:
    1. Right-click a frame block with the Gate Linker
    2. Press "Copy this gate's mark" (copies the mark to clipboard)

  On the target gate:
    3. Right-click the target frame with the Gate Linker
    4. Paste the mark into the box (or use the "Paste" button)
    5. Press "Link"

Both directions are linked automatically (if the partner is another
save, the reverse link is set up next time that save loads). Any
block of a gate's frame can be used to operate on it.


--------------------------------------------------------------------
4. Descriptions and target info
--------------------------------------------------------------------
Set a free-form note for a gate from the Gate Linker screen. Aiming
at a linked portal shows "Save name (x, y, z) 'note'" on the action bar.


--------------------------------------------------------------------
5. Unlinking and break protection
--------------------------------------------------------------------
To unlink without breaking the frame, use "Unlink" in the Gate Linker
screen. Breaking the frame itself also unlinks it (breaking an
unrelated decorative frame block doesn't affect the real gate).

Break protection can be toggled from the Gate Linker screen, the
/worldstitch protect command, or the config. Protected frames behave
like bedrock - completely unresponsive to hits. The portal surface
itself is always unbreakable regardless of this setting (same as a
vanilla Nether portal).


--------------------------------------------------------------------
6. Commands
--------------------------------------------------------------------
These target whichever gate you're looking at, no Gate Linker needed.

  /worldstitch mark                 print this gate's mark to chat
  /worldstitch link <mark>          link to the given mark
  /worldstitch unlink                unlink this gate
  /worldstitch protect               toggle break protection
  /worldstitch residency             show the departed-save setting
  /worldstitch residency allow       allow entering departed saves directly
  /worldstitch residency block       restore the default auto-redirect
  /worldstitch residency clear       clear this save's departure record


--------------------------------------------------------------------
7. Cross-save and cross-dimension travel
--------------------------------------------------------------------
Warping to another save saves and closes the current one, then opens
the target save (singleplayer only). The save you left is marked
"departed" - opening it directly afterward auto-redirects you (see
the commands above to change this).

Cross-dimension links work between any of Overworld/Nether/End; a
same-save link switches instantly like a vanilla portal.

CAUTION: cross-save warping carries your character data (inventory,
XP, ender chest, etc.) over as-is, overwriting whatever was on the
target save the first time. Unlinking afterward does NOT restore the
original data, so both saves end up with the same items - which can
lead to unintended duplication. Check the target save's inventory
before warping.


--------------------------------------------------------------------
8. Config (config/worldstitch.json)
--------------------------------------------------------------------
Also editable from ModMenu's settings screen if installed (the file
is created on first launch).

  allowCrossDimensionTravel   (default true)  allow Nether/End travel
  allowCrossSaveTravel        (default true)  allow travel to another save
  allowEnteringDepartedSave   (default false) allow entering departed saves
  portalCooldownTicks         (default 60)    re-warp cooldown (20=1s)
  minGateInnerWidth/Height    (default 2/3)   minimum gate inner size
  maxGateInnerSize            (default 21)    maximum gate inner size
  reverseLinkMaxRetryTicks    (default 200)   reverse-link retry limit
  frameBreakProtectionEnabled (default true)  enable/disable break protection


--------------------------------------------------------------------
9. Notes
--------------------------------------------------------------------
  - Singleplayer only (cross-save travel relies on the integrated server)
  - Back up your saves before warping to another save, just in case
  - In-game text follows Minecraft's Language setting (Japanese or English)


====================================================================
 Target: Minecraft 26.3 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.3
====================================================================
