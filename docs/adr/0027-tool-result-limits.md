---
title: "Tool result limits"
description: "Fit tool counts and prompt results to the model's context."
---

Status: Accepted

## Decision

The Model profile includes Result limits. Micro and Small use 15% of context per result; Micro has a 400-token floor. Mid and Frontier use 10%, capped at 8,000 and 25,000 tokens respectively. Unknown context uses the existing 24,576-token compression fallback. Earlier turns receive 25% of the per-result budget.

Micro, Small and Mid request at most 3, 5 and 20 items. Frontier keeps tool defaults. The core registry identifies count parameters and paging support; external integer `limit`, `max_results`, `maxResults`, `top_k` and `page_size` parameters are recognized from their schemas (not `count`, which often means how many things to create). Line counts such as `read_file`'s are bounded by the text budget and paging instead. The harness clamps arguments, including catalog and Script calls, rather than relying on model instructions.

Text is shortened only in model prompts, preserving head and tail with an omission marker. Provider token estimates take priority over the characters-per-token fallback. Stored results and client output stay complete; existing tool and Script bridge caps remain upper bounds. Paging advice appears only for tools registered as supporting it.
