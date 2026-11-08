---
title: "Apple on-device models on Mac"
description: "Set up Apple's fm command for local inference in Kinuvo."
---

A Server running on macOS automatically offers **Apple → Apple on-device model** when Apple's `/usr/bin/fm` command is installed. This works with the desktop app's local Server and the standalone `kinuvo` Server. No API key or separate model server is needed. Requests use `fm respond` on that Mac; Kinuvo keeps the Session transcript and runs the supported tools.

## Set up the Mac running the Server

Apple includes `fm` starting with macOS 27. The on-device model requires a Mac that supports Apple Intelligence and a supported language and region. Setup belongs to the Mac running the Server, even when you use Kinuvo from another Device.

1. Open **System Settings → Apple Intelligence & Siri** and enable Apple Intelligence. Allow its model download to finish.
2. Open Terminal and check that Apple's command exists:

   ```sh
   /usr/bin/fm license --status
   ```

3. If the license is not agreed, review and accept Apple's terms yourself:

   ```sh
   sudo /usr/bin/fm license
   ```

   Acceptance applies to **every user on that Mac** and requires an administrator. Kinuvo never accepts the terms or runs `sudo` for you. You can read the terms without accepting them with `fm license --show`.

4. Check availability, then try a small response as the macOS user running Kinuvo:

   ```sh
   /usr/bin/fm available
   /usr/bin/fm respond --no-stream --text 'Say hello briefly.'
   ```

5. In Kinuvo's onboarding or Agent model picker, refresh models and choose **Apple → Apple on-device model**. An unaccepted license or unavailable model appears with setup instructions. After completing setup, refresh models again. Restart the Server if `fm` was installed while it was running.

On a remote Server, run these commands on the Server's Mac. Accepting the license on your phone or another computer does not configure that Server. A background service uses its macOS user's Apple Intelligence setup.

## Availability and limits

If `/usr/bin/fm` is missing, this provider is not offered. Use a macOS version that includes Apple's command, or choose another provider or Server. Installing another program named `fm` on your PATH does not enable this integration.

If the model is unavailable, check Apple Intelligence settings, download status, device eligibility, and language/region support. Run `fm available` for Apple's details. Kinuvo checks readiness again on refresh and before generation; it does not switch providers automatically.

Kinuvo exposes only the on-device `system` model through `fm`; Private Cloud Compute is not included. It is a no-setup option for quick, private conversations, not a replacement for larger models:

- **Basic prompt.** Instead of Kinuvo's full instructions, the model gets a short prompt: the Agent's name, your User Notes and Memory, its tools and today's date. Identity and Soul aren't sent.
- **Few tools.** It takes simple typed tools, Memory and web first: at most 6 on the system model, 16 on Private Cloud Compute. No Skills, Skill catalog or `show_ui`.
- **Text only.** Browser screenshots stay in the Session archive; the model receives the page text instead.
- **Latest message only.** Each request carries your latest message and the current turn's tool steps. Earlier turns stay in the Session but aren't sent, because the model tends to copy earlier replies. Facts you want it to keep belong in Memory or User Notes.
- **Small context.** It has 4,096 tokens, counted with `fm count-tokens`. Large tool results such as full web pages can exceed it.

Replies appear when generation finishes. For browsing, files and multi-step tasks, choose a larger model in the Agent's settings.

See Apple's [fm introduction](https://developer.apple.com/videos/play/wwdc2026/334/) and `man fm` for the CLI, and [models and provider plugins](../reference/models.md) for model selection and refresh behavior. The iOS app uses Apple's native Foundation Models framework and does not need the Mac CLI license step.
