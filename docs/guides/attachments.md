---
title: "Attachments"
description: "Add photos and files to a Session and understand upload limits."
---

The composer’s + menu offers Photos and Files on iOS, Android, and desktop. Selected images have previews; files show their name and size. Remove a selection before sending, or send attachments without text. Failed sends retain the draft and selections. Successfully uploaded files are reused when retrying a failed send.

Supported inputs are JPEG, PNG, GIF, WebP, PDF, DOCX, XLSX, PPTX, CSV, JSON, Markdown, text, and common source-code files. Each message accepts up to four files, at most 10 MB each and 20 MB combined. UTF-8 text and code files are limited to 256 KB.

The iOS Photos picker converts photos to JPEG, resizes them to at most 2048 pixels, and drops photo metadata. The Server similarly resizes JPEG, PNG, and GIF inputs and saves a fresh JPEG without metadata; GIF inputs use the first frame. WebP and other files retain their uploaded bytes. The transcript’s download action saves the stored version through the platform’s file dialog or iOS share sheet.

Attachments stay in the Server home's `attachments/` folder until you remove them yourself; automatic cleanup is not available. Keep that folder in your backups.

Images and files are sent to the selected model provider when needed to answer your message. Whether it can interpret a document depends on that provider and model. Compaction may summarize an older attachment turn, but the full transcript and stored downloads remain available.

Tools can also show files, such as an image or PDF from an Anytype Note.
