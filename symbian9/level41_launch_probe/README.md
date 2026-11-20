# Level 41 E65 launch probe

This is a temporary diagnostic SISX, separate from the native Level 41 scene.
It uses the same raw `e32-import` startup, AppArc registration and SIS packer,
but links only EUser, WindowServer and GDI. If it opens on the E65, the raw
startup/registration path works and the remaining suspect is an import or
runtime dependency unique to the renderer/DSA app. If it shows the same
“Feature not supported” message, the failure is below the renderer—likely the
launch ABI, registration, or an API/runtime requirement shared with this
minimal executable.

When it launches, it displays a blue screen labelled `RAW E32 LAUNCH OK` and
waits for a key. The v1.0.0 event loop compared `EEventKey`'s handle with the
child window handle (2), but WindowServer tags key events with the focused
window group's client handle (1). Version 1.0.1 checks the group handle instead;
its E65 input behavior still needs device confirmation.

This is a diagnostic only, not the Level 41 renderer or a compatibility claim
for S60v3 devices.
