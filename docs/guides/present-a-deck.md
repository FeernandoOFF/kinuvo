---
title: "Present a reveal.js deck"
description: "Show an HTML slide deck on one device and drive it, with speaker notes, from your phone."
---

A reveal.js deck saved as an HTML Artifact can be shown on one device and driven from another that connects to the same Server.

1. Open the deck on the screen your audience sees, for example the desktop app in full screen.
2. Open the same Artifact on your phone and tap **Present**. The deck stays at the top; below it you get the current slide's speaker notes, a preview of the next slide, and large Previous and Next buttons.
3. Tap Next. The deck on the other device moves at once. Moving the deck on any device moves all of them, and a device that opens the deck later starts on the current slide.

Speaker notes are the `<aside class="notes">` elements of each slide. Every device loads the deck itself, so reveal.js (and anything else the deck loads) must be reachable from each of them. The current slide is remembered while the Server runs and is forgotten when it restarts. The web app does not take part.
