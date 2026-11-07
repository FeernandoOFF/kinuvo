---
title: "Privacy policy"
description: "How Kinuvo handles information on your Device, your Server, and connected services."
---

> Draft for release review. This policy has not been published or given an effective date. It must be checked against the final store builds before publication.

Kinuvo is a free, self-hosted personal AI assistant published by **Fernando Obregon Fernandez**. Privacy questions and requests can be sent to **me@feernandooff.com**. This policy covers the Kinuvo mobile apps and the documentation website. It explains both information handled by the publisher and information processed by the software on your behalf.

## Where your information goes

Kinuvo can run a Server on your Device or connect to a Server you choose. The Server holds Agents, Sessions, configuration and related files. There is no publisher-operated Kinuvo account or hosted chat service in this release. The publisher does not receive your conversations simply because you use the app.

Your choices determine the other recipients. When you select an external AI provider, the Server sends the information needed for its requests to that provider. When you enable a tool or integration, information needed for that action can be sent to the connected service. If someone else operates your Server, they may have access to its stored information. Their practices and the connected services' terms and privacy policies also apply.

Local inference can process model requests on a supported Device. Android local-model downloads contact Hugging Face and can disclose your IP address and requested model to that host. The Server can also fetch model metadata from models.dev; these downloads are separate from model inference and do not require sending your conversations. Enabling web search, a remote tool, an external model, or a remote Server can still send information off that Device. Kinuvo does not promise that all processing stays on-device.

## Information processed by the app

- **Conversations and work:** messages, AI replies, tool requests and results, Session titles, Memory, User Notes, Skills, Tasks, Routines, Knowledge and Artifacts. These support the features you use and can form part of later model requests, including Reflection after a conversation.
- **Files and media:** photos, documents and other attachments you choose to provide. Supported content can be sent to your Server, selected model provider or tools. A photo you choose to take uses the camera; Kinuvo does not automatically upload your entire photo library.
- **Device information and connections:** Device names, timezone, Server addresses, pairing credentials, access grants and connection status. These enable pairing, authentication and access management. Recipients of network connections can also receive ordinary network information such as your IP address.
- **Provider and integration credentials:** sign-in tokens, API keys, configured endpoints and Secret references used to access services you select. Their storage depends on the platform and credential type; some use the operating system keychain or keystore and some are stored in the Server's private files. Kinuvo does not claim every Server file is encrypted at rest.
- **Optional contacts:** with your permission, enabled Agents can search contact names, phone numbers and email addresses. Relevant results can reach the Server, its Session records and the model processing the request. You can deny or revoke access.
- **Third-party model providers:** before the mobile apps first send a message to a third-party model provider, such as OpenAI, Kinuvo names the provider and asks for your permission. The provider then receives your messages, attachments, the Agent's Memory and the results of tools the Agent uses, under its own terms and privacy policy. Kinuvo asks once for each provider on each Device. Apple's models need no permission: the on-device model keeps conversations on the Device, and Private Cloud Compute is Apple's own service. The iOS app does not access Apple Health.
- **Optional voice features:** microphone input, audio and transcripts needed for supported Dictation or Voice chat. Depending on the selected speech configuration and platform, processing can involve operating system speech services or the selected Server and its speech provider. Voice transcripts can remain in Sessions. Voice availability differs between platforms and Server configurations.
- **Optional tools and Screens:** connected tools may access the files, notes, websites or other information needed for an action. Watching a remote Computer or Browser can transmit screenshots, page content and interaction data through the Server. The Google Play Android build excludes control of the phone through an Accessibility Service. iOS does not provide general control of its own screen through such a service.
- **Diagnostics:** Android and JVM Servers record local Agent trace metadata, including Agent/Session/run identifiers, model and tool names, timing, token usage and success/error status. This trace exporter excludes prompts, replies, attachments, tool arguments/results and exception messages. It is enabled by default, can be disabled in Server configuration, and rotates four files of approximately 4 MiB each. Ordinary application or Server logs are separate and may contain operational information or error details. No publisher analytics or crash-report upload service was identified in the release source audit; sending diagnostics to support is voluntary.

## Optional outside services

The configured AI provider may be OpenAI/ChatGPT or a compatible endpoint you choose; its policies govern its processing and retention. Connected services can include MCP servers, Firecrawl, Anytype, Task providers and ntfy, depending on the Server's capabilities. This is not an exhaustive list of endpoints you can configure. Review a service before connecting it and avoid sending information you do not want it to receive.

If you configure ntfy, notification titles and bodies are sent to the selected ntfy server and topic. The default service URL is ntfy.sh. A public, unprotected topic can be read by someone who knows its name; use a protected service/topic where appropriate.

External websites and content opened through tools, Browsers or Artifacts can contact their own services. Kinuvo's Approvals and Device permissions help control actions, but do not replace the privacy policies of those services.

## Native mobile Notifications

If you allow Notifications, the app obtains a delivery token from Apple Push Notification service (APNs) on iOS or Firebase Cloud Messaging (FCM) on Android. Your paired Server receives a subscription containing a delivery endpoint and encryption information. The Server encrypts notification content before sending it through the publisher-operated relay at push.kinuvo.feernandooff.com, hosted on Cloudflare Workers. The relay forwards encrypted payloads to Apple or Google; the app decrypts them on your Device.

The relay handles delivery tokens, encrypted payloads and network metadata needed for delivery and rate limiting. Its application code has no persistent message database. Hosting and delivery providers may process operational metadata under their own policies; this is not a promise that their infrastructure retains no logs. Apple/Google transport retention and delivery settings apply. Disabling Notifications or the Server's push setting stops the corresponding delivery behavior. Optional ntfy delivery is separate and can send plaintext notification content to your configured ntfy service.

The release includes Google's Firebase Messaging SDK for Android delivery. Review [Google's Firebase privacy information](https://firebase.google.com/support/privacy), [Apple's privacy information](https://www.apple.com/legal/privacy/), and [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/) for those providers' handling. Notification delivery is for app functionality, not advertising tracking.

## Storage, retention and deletion

Your Server stores its data until you or its operator remove it; Kinuvo does not apply a universal timed expiry to Sessions, Memory, files or attachments. Starting a new Session does not clear an Agent's Memory. Deleting a Session removes its transcript but does not necessarily remove attachments, Memory derived from it, Knowledge, Artifacts, traces, external tool changes or backups.

You can use available Agent, Session, Knowledge and Artifact controls to remove individual items. For a complete reset of a self-hosted Server, stop it, back up anything you want to keep, and remove its data directory under your control. An on-device Server's app files can be removed through the platform's app-data removal/uninstall process. Operating system keychain entries, other Devices, external providers and backups may need separate removal or revocation. Removing a remote Server connection or revoking a Device ends access; it does not erase the remote Server's content. Ask that Server's operator to remove it.

The publisher cannot erase files from a Server or provider account that the publisher does not operate. External providers and service operators determine their own retention and deletion procedures.

If you contact support, the publisher receives your email address, correspondence and any information or diagnostic files you choose to include. These are used to answer your request and investigate the issue. Support correspondence and supplied diagnostics are deleted within **90 days after the request is resolved**, unless retention is required by a legal obligation. Please do not send passwords, API keys, identity documents or unrelated personal information. You can request earlier deletion where applicable.

## Security

Use a trusted Server and secure network connection. Public connections require secure WebSockets, but explicitly configured LAN or Tailscale connections can use cleartext WebSockets. Consequently, this policy does not claim that every transmission is encrypted or end-to-end encrypted. Protect the Server, its files and backups; revoke lost Devices and unnecessary service credentials.

## Documentation website

The documentation and these pages are hosted on Cloudflare at kinuvo.feernandooff.com. Cloudflare processes ordinary request metadata such as IP addresses to deliver and secure the website under its [privacy policy](https://www.cloudflare.com/privacypolicy/). The site stores your selected Agent appearance in browser local storage; you can remove it through your browser's site-data controls. The source audit found no advertising or visitor analytics integration in the documentation site.

## Your choices and privacy rights

You can choose your Server and model provider, enable only the integrations you need, revoke Device permissions, disconnect services and request deletion of support information held by the publisher. Where applicable law provides them, rights can include access, correction, deletion, restriction, objection and data portability, and withdrawal of consent without affecting earlier lawful processing. You may complain to your local data protection authority; in Ireland this is the [Data Protection Commission](https://www.dataprotection.ie/).

For information the publisher controls, the purposes are responding to support requests, maintaining security and meeting legal obligations. The relevant bases are legitimate interests in providing support and securing the service, steps requested by you where necessary for a service relationship, consent where required for voluntarily supplied sensitive information, and legal obligations where applicable. Do not send sensitive data to support unless it is necessary and you choose to do so. The lawful basis and legal roles for your own Server or outside services depend on who operates them and how they are used.

Network services and email/hosting providers may process information outside your country. Their applicable notices explain locations and transfer safeguards; a user-selected endpoint is not guaranteed to remain in the EEA.

Kinuvo is intended for adults aged 18 and over and is not directed at children. The publisher does not sell personal information or use it for advertising or cross-app advertising tracking. If you believe a child has supplied personal information to the publisher, contact the privacy address above.

## Changes and contact

Material changes will be described in an updated policy with an effective date when published. Contact **Fernando Obregon Fernandez, me@feernandooff.com** for questions about this policy or publisher-held information. See [Support](support.md) for setup and deletion help.
