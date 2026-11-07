---
title: "Privacy policy"
description: "How Kinuvo handles information on your Device, your Server, and connected services."
---

**Effective date: November 7, 2026.**

Kinuvo is a free, self-hosted personal AI assistant published by **Fernando Obregon Fernandez**. Privacy questions and requests can be sent to **me@feernandooff.com**. This policy covers the Kinuvo mobile apps and the documentation website. It explains both information handled by the publisher and information processed by the software on your behalf.

## Where your information goes

Kinuvo can run a Server on your Device or connect to a Server you choose. The Server holds Agents, Sessions, configuration and related files. There is no publisher-operated Kinuvo account or hosted chat service in this release. The publisher does not receive your conversations simply because you use the app. On Android, you can enable “Reach this Server from other Devices” to let Devices on your Wi-Fi or Tailscale network pair with the on-device Server. Paired management Devices can access its Agents, Sessions and related data; LAN connections may be unencrypted. Enable sharing only on a trusted network and revoke Devices you no longer trust.

Your choices determine the other recipients. When you select an external AI provider, the Server sends the information needed for its requests to that provider. When you enable a tool or integration, information needed for that action can be sent to the connected service. If someone else operates your Server, they may have access to its stored information. Their practices and the connected services' terms and privacy policies also apply.

Local inference can process model requests on a supported Device. Android local-model downloads contact Hugging Face and can disclose your IP address and requested model to that host. The Server can also fetch model metadata from models.dev; these downloads are separate from model inference and do not require sending your conversations. Enabling web search, a remote tool, an external model, or a remote Server can still send information off that Device. Kinuvo does not promise that all processing stays on-device.

## Information processed by the app

- **Conversations and work:** messages, AI replies, tool requests and results, Session titles, Memory, User Notes, Skills, Tasks, Routines, Knowledge and Artifacts. These support the features you use and can form part of later model requests, including Reflection after a conversation.
- **Files and media:** photos, documents and other attachments you choose to provide. Supported content can be sent to your Server, selected model provider or tools. Taking a photo or scanning a pairing QR code uses the camera; Kinuvo does not automatically upload your entire photo library.
- **Device information and connections:** Device names, timezone, Server addresses, pairing credentials, access grants and connection status. These enable pairing, authentication and access management. Recipients of network connections can also receive ordinary network information such as your IP address.
- **Provider and integration credentials:** sign-in tokens, API keys, configured endpoints and Secret references used to access services you select. Their storage depends on the platform and credential type; some use the operating system keychain or keystore and some are stored in the Server's private files. Kinuvo does not claim every Server file is encrypted at rest.
- **Optional contacts:** with your permission, enabled Agents can search contact names, phone numbers and email addresses. Relevant results can reach the Server, its Session records and the model processing the request. You can deny or revoke access.
- **Third-party model providers:** For ordinary Session message sends, the mobile apps ask for your permission before the first send to an external model provider. The prompt names the provider when its identity is available, such as OpenAI; when it is not yet available, the current prompt uses a generic provider label. This prompt is a mobile message-send control, not Server-wide permission. Paths outside it include Server transcription, Live voice, Routines (scheduled or run on demand), Reflection (which may use a separately configured provider), and requests from desktop/web clients or other paired Devices. These paths can send their audio, conversation context or work to configured providers without this Device’s prompt. Allowing the generic unknown-provider prompt is remembered for later sends whose provider identity is also unknown. The provider then receives your messages, attachments, the Agent's Memory, User Notes, relevant Knowledge, other Session context and the results of tools the Agent uses, under its own terms and privacy policy. Kinuvo asks once for each provider on each Device. The prompt exempts Apple's models and local inference. On-device inference processes model requests on the Server Device; configured tools and integrations can still send information elsewhere. Private Cloud Compute, where enabled, uses Apple's service. The iOS app does not access Apple Health.
- **Optional voice features:** microphone input, audio and transcripts needed for supported Dictation or Voice chat. Depending on the selected speech configuration and platform, processing can involve operating system speech services or the selected Server and its speech provider. Voice transcripts can remain in Sessions. Voice availability differs between platforms and Server configurations.
- **Optional tools and Screens:** connected tools may access the files, notes, websites or other information needed for an action. Watching a remote Computer or Browser can transmit screenshots, page content and interaction data through the Server. Android also offers an on-phone Browser with a separate profile for each Agent. Browser cookies and sign-ins are stored in app data; websites receive normal browsing requests, and page content or screenshots can enter model requests. The Google Play Android build excludes control of the phone through an Accessibility Service. iOS does not provide general control of its own screen through such a service.
- **Diagnostics:** Android and JVM Servers record local Agent trace metadata, including Agent/Session/run identifiers, model and tool names, timing, token usage and success/error status. This trace exporter excludes prompts, replies, attachments, tool arguments/results and exception messages. It is enabled by default, can be disabled in Server configuration, and rotates four files of approximately 4 MiB each. Ordinary application or Server logs are separate and may contain operational information or error details. The publisher does not operate an app analytics or automatic crash-report upload service. Third-party SDK diagnostics, described below, are separate; sending diagnostics to support is voluntary.

## Optional outside services

The configured AI provider may be OpenAI/ChatGPT or a compatible endpoint you choose; its policies govern its processing and retention. Connected services can include MCP servers, Firecrawl, Anytype, Todoist, CalDAV task services and ntfy, depending on the Server's capabilities. This is not an exhaustive list of endpoints you can configure. Review a service before connecting it and avoid sending information you do not want it to receive.

Task integrations can read and change task lists, titles, descriptions, due dates and completion status in the selected service. The built-in CalDAV integration uses task records (VTODO), rather than a general calendar-event reader. Tool results can enter Session records and model requests.

If you configure ntfy, notification titles and bodies are sent to the selected ntfy server and topic. The default service URL is ntfy.sh. A public, unprotected topic can be read by someone who knows its name; use a protected service/topic where appropriate.

External websites and content opened through tools, Browsers or Artifacts can contact their own services. Kinuvo's Approvals and Device permissions help control actions, but do not replace the privacy policies of those services.

## Android pairing scanner

Android pairing QR scanning uses Google Play services' code scanner. Google states that scanning images and results are processed on-device and are not sent to Google. The SDK can send technical information to Google for diagnostics and usage analytics, including device/app information, identifiers, performance metrics, configuration, feature events and error codes. See [Google's scanner documentation](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) and [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). This SDK processing is separate from Kinuvo's local Agent traces and voluntary support diagnostics.

## Native mobile Notifications

The app registers with Apple Push Notification service (APNs) on iOS or initializes Firebase Cloud Messaging (FCM) on Android and requests a delivery token. Registration and provider processing of identifiers and configuration information can occur before you allow Notifications. Notification permission controls whether Kinuvo subscribes your paired Server for delivery and displays Notifications. When enabled, your paired Server receives a subscription containing a delivery endpoint and encryption information. The Server encrypts notification content before sending it through the publisher-operated relay at push.kinuvo.feernandooff.com, hosted on Cloudflare Workers. The relay forwards encrypted payloads to Apple or Google; the app decrypts them on your Device.

The relay handles delivery tokens, encrypted payloads and network metadata needed for delivery and rate limiting. Its application code has no persistent message database. Relay operational logs can include request URLs containing delivery tokens, response metadata and delivery errors. Cloudflare also processes the sending Server's IP address. These operational records support delivery troubleshooting, security and abuse handling. Cloudflare documents Workers Logs retention of up to seven days, depending on the plan. See [Workers Logs](https://developers.cloudflare.com/workers/observability/logs/workers-logs/). Hosting and delivery providers may process additional operational metadata under their own policies; this is not a promise that their infrastructure retains no logs. Apple/Google transport retention and delivery settings apply. Disabling Notifications or the Server's push setting stops the corresponding delivery behavior. Optional ntfy delivery is separate and can send plaintext notification content to your configured ntfy service.

The release includes Google's Firebase Messaging SDK for Android delivery. Review [Google's Firebase privacy information](https://firebase.google.com/support/privacy), [Apple's privacy information](https://www.apple.com/legal/privacy/), and [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/) for those providers' handling. Notification delivery is for app functionality, not advertising tracking.

## Storage, retention and deletion

Your Server stores its data until you or its operator remove it; Kinuvo does not apply a universal timed expiry to Sessions, Memory, files or attachments. Starting a new Session does not clear an Agent's Memory. Deleting a Session removes its transcript but does not necessarily remove attachments, Memory derived from it, Knowledge, Artifacts, traces, external tool changes or backups.

You can use available Agent, Session, Knowledge and Artifact controls to remove individual items. For a complete reset of a self-hosted Server, stop it, back up anything you want to keep, and remove its data directory under your control. An on-device Server's app files can be removed through the platform's app-data removal/uninstall process. On iOS, Server files in Application Support are not excluded from backup and can be included in iCloud or computer backups under your system settings. Android disables ordinary app backup; do not assume every operating-system or device-transfer copy has been removed. Operating system keychain entries, other Devices, external providers and backups may need separate removal or revocation. Removing a remote Server connection or revoking a Device ends access; it does not erase the remote Server's content. Ask that Server's operator to remove it.

The publisher cannot erase files from a Server or provider account that the publisher does not operate. External providers and service operators determine their own retention and deletion procedures.

If you contact support, the publisher receives your email address, correspondence and any information or diagnostic files you choose to include. These are used to answer your request and investigate the issue. Support correspondence and supplied diagnostics are deleted within **90 days after the request is resolved**, unless retention is required by a legal obligation. Please do not send passwords, API keys, identity documents or unrelated personal information. You can request earlier deletion where applicable.

## Security

Use a trusted Server and secure network connection. Ordinary Device-to-Server connections require secure WebSockets for public addresses, but LAN or Tailscale connections can use cleartext WebSockets. Separately configured Server-to-Server harness connections also permit public cleartext WebSockets; a secure Device connection does not secure that onward connection. Consequently, this policy does not claim that every transmission is encrypted or end-to-end encrypted. Protect the Server, its files and backups; revoke lost Devices and unnecessary service credentials.

## Documentation website

The documentation and these pages are hosted on Cloudflare at kinuvo.feernandooff.com. Cloudflare processes ordinary request metadata such as IP addresses to deliver and secure the website under its [privacy policy](https://www.cloudflare.com/privacypolicy/). The site stores your selected Agent appearance in browser local storage; you can remove it through your browser's site-data controls. The documentation site does not include advertising or visitor analytics integrations.

## Your choices and privacy rights

You can choose your Server and model provider, enable only the integrations you need, revoke Device permissions, disconnect services and request deletion of support information held by the publisher. Where applicable law provides them, rights can include access, correction, deletion, restriction, objection and data portability, and withdrawal of consent without affecting earlier lawful processing. You may complain to your local data protection authority; in Ireland this is the [Data Protection Commission](https://www.dataprotection.ie/).

For information the publisher controls, the purposes are responding to support requests, operating and troubleshooting the push relay, preventing delivery abuse, maintaining security and meeting legal obligations. The relevant bases are legitimate interests in providing support and securing the service, steps requested by you where necessary for a service relationship, consent where required for voluntarily supplied sensitive information, and legal obligations where applicable. Do not send sensitive data to support unless it is necessary and you choose to do so. The lawful basis and legal roles for your own Server or outside services depend on who operates them and how they are used.

Network services and email/hosting providers may process information outside your country. Their applicable notices explain locations and transfer safeguards; a user-selected endpoint is not guaranteed to remain in the EEA.

Kinuvo is intended for adults aged 18 and over and is not directed at children. The publisher does not sell personal information or use it for advertising or cross-app advertising tracking. If you believe a child has supplied personal information to the publisher, contact the privacy address above.

## Changes and contact

Material changes will be described in an updated policy with a revised effective date. Contact **Fernando Obregon Fernandez, me@feernandooff.com** for questions about this policy or publisher-held information. See [Support](support.md) for setup and deletion help.
