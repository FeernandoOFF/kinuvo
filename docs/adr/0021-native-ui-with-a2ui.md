# Native UI with A2UI

Agents show Surfaces with A2UI v1.0 through ynagai's `a2ui-core` and `a2ui-compose` 0.3.0. Kinuvo renders all 18 standard basic catalog components with its own design kit, keeping forms native and consistent with the chat. Devices advertise `BasicCatalog.definition` unchanged, including its standard catalog ID. The tool description omits schema prose while validation uses the complete advertised schemas.

Image URLs and video posters are fetched through the Server, never directly by a Device. The JVM and Android proxy accepts public HTTP(S) raster images only, checks every redirect and resolved address, pins connections through the guarded resolver, and limits response size and request duration. The client caches images for its connected Server lifetime. The iOS embedded Server fails closed until it has an equivalent guarded transport.

Video and AudioPlayer render as media cards that open HTTP(S) URLs with the platform URL opener. DateTimeInput uses validated ISO 8601 inputs; Slider snaps to the catalog’s range divisions. Tabs keep local selection; Modal opens its content through the app’s Popover host.

Devices advertise inline catalogs through the authenticated `surfaces.catalogs` request after each successful connect, before the connection becomes ready. The connect frame contains no catalogs so it stays within the 4 KB pre-authentication limit. Catalogs belong to active connections and are removed on disconnect. Device messages use the requesting connection’s catalogs, including an empty list. Runs without a requesting connection (routines, tasks and remote runs) use the most recently connected active connection advertising any catalogs; without one, `show_ui` is unavailable. The Server validates `show_ui` messages against those catalogs; clients render Surfaces directly from the tool call's arguments. Actions return through the normal user message path with `userMessage` (or name) as text and an optional `SurfaceAction`: call ID, Surface ID, action name, source component, resolved context and data model. The chat shows a compact user action line; model prompts include the text and an action annotation with context JSON, including history replay and external harnesses. Each call creates independent Surfaces. The latest submitted data model per call and Surface restores inputs after restart; the renderer cache retains edits while the Session is open. Unsubmitted edits are not persisted. A newer submitted model replaces local unsubmitted edits on every Device, including cached renderers. Invalid DateTimeInput drafts block Surface submissions until corrected.

Future work: native media playback, a guarded image transport for the iOS embedded Server, Kinuvo domain components, Surface updates across turns, a SwiftUI renderer.

Devices also advertise `https://kinuvo.com/a2ui/v1/catalog.json`. Its definition is composed in `shared/app`, using only a2ui-core, from the unchanged basic schemas plus Badge, Spacer, ListItem, Table, TableRow, TableCell and Transition. Each Surface selects one catalog; the Kinuvo catalog includes the basic components so mixed layouts need no per-component catalog IDs. Its `anyComponent` schema includes every added type, keeping envelope and per-component validation consistent. Compose has a renderer for every advertised component. The Kinuvo ChoicePicker additionally accepts `displayStyle: "dropdown"`; the basic catalog remains unchanged.

Table rows use the standard A2UI children array or data-model template. Cells accept arbitrary children including inputs and actions. Tables scroll horizontally, with 160 dp columns so controls remain readable on phones and columns stay aligned. ListItem composes leading/trailing children around title/subtitle; its optional action uses normal Surface submission. Transition crossfades changing child references. Forms use Column/Row, bound inputs, Button checks and actions. Card status uses Badge and confirm/cancel use Buttons in its child layout; no separate Form or CardAction wire type is needed.

ChatKit element mapping:

| ChatKit | A2UI |
| --- | --- |
| Box, Col, Row | Column, Row |
| Text, Markdown, Title, Caption, Label | Text (h1–h5/caption variants; input labels use label) |
| Input, Textarea | TextField (longText for multiline) |
| Checkbox, DatePicker | CheckBox, DateTimeInput |
| Select, RadioGroup | ChoicePicker |
| ListView, ListViewItem | List, ListItem |
| Card, CardAction, Form | Card + Column/Row + Badge + Button checks/actions |
| Button, Divider, Icon, Image | Basic components of the same name |
| Badge, Spacer, Table, TableRow, TableCell, Transition | Kinuvo components of the same name |
