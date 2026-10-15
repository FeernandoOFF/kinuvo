# Rive avatar

The empty Session screen plays the bundled `shared/ui/composeResources/files/avatars.riv`. It loads from Compose resources without a network request. The default artboard is Orange Blob; its Avatar state machine plays Idle Blink on a six-second loop and exposes inputs `lookX` and `lookY` (numbers, 0-100, 50 centred; eyes and pupils follow) and `poke` (trigger; eyes become `>.<` briefly). A pointer-down listener on the body fires `poke`, so tapping the avatar needs no app code. The app sets `lookX`/`lookY` from `Gaze` (`LocalRiveGaze`): down while the composer is focused, left while the sidebar is.

The editable Rive project is not in the repository; after editing it, copy the exported `avatars.riv` to `shared/ui/composeResources/files/avatars.riv`.

For now, the UI randomly assigns each Agent an artboard from Orange Blob, Lilac Sprout, Mint Cushion and Coral Bean, seeded by the Agent's ID. The same Agent keeps its avatar across screens and app restarts; the settings overview and empty Session both use this assignment. iOS and web still use the shared Orange Blob still.

Rive-CMP 0.5.0 renders the file on Android and desktop; their initialization lives in `shared/ui`. Its desktop runtime only ships for Apple silicon, so other desktops, iOS and web show a still image instead: the `.png` next to the `.riv` in Compose resources.

Rive types stay inside the UI layer, so a future native iOS client can reuse the asset with the official runtime.
