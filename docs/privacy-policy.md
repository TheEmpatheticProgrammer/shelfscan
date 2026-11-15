# ShelfScan privacy policy (draft)

> **Draft for the app's operator to review before publishing.** Replace every `[bracketed]` part, check each
> statement against how your server is actually deployed, and have someone qualified review it if in doubt.

_Last updated: [date]_

ShelfScan ("the app") helps you catalog the physical books you own. This policy explains what the app sends where.

## What stays on your phone
Your library (titles, authors, covers, reading status, notes the app writes such as synopses) and your Want to read
list are stored **only on your phone**. They are included in your phone's Android backup if you have backup turned
on, and in any export file you choose to create.

## What is sent, and why
The app works by sending some information to the ShelfScan server at `[server address]`, operated by `[operator]`:

- **Photos you take or choose for scanning.** Sent to the server, which passes them to an AI model to read the book
  titles. The AI model is `[a model the operator runs itself / provider name, e.g. Anthropic]`. The ShelfScan server
  does not store your photos. `[If a third-party provider is used: link its privacy policy and state its retention.]`
  If the server can't be reached, photos wait on your phone until they can be sent, and are deleted after you review
  the result or discard the scan.
- **Book titles and authors** from your library and Want to read list, when you ask for recommendations, similar
  books or upcoming releases. Sent to the server and the AI model to produce suggestions. The server may keep the
  resulting suggestions for up to 7 days so repeated requests are fast; they're stored under a one-way hash of the
  book list, not your library itself.
- **What you type into search, and scanned barcodes (ISBNs).** Sent to the server.

To look up book details and covers, the server queries **Google Books** and **Open Library** with book titles,
authors or ISBNs. These requests come from the server, not your phone, and contain nothing that identifies you. Your
phone loads cover images directly from those services' image addresses.

## What ShelfScan does not do
No accounts, no ads, no analytics or tracking SDKs, and no selling or sharing of your data for advertising. The app
asks for no camera permission: photos come from your camera app or photo picker, and barcodes are read by Google
Play services' code scanner on your phone.

## Server logs
The server logs technical information (request times, errors, and the titles it read from a photo) to keep it
working, kept for `[N days]`.

## Children
ShelfScan isn't directed at children under 13.

## Contact
Questions or requests: `[contact email]`.

---

## Draft answers for the Play Console Data safety form
_Check these against your deployment before submitting._

| Question | Draft answer |
|---|---|
| Does the app collect or share user data? | Yes, collects (sent off the device) |
| Photos | Collected; **not shared** (the AI model and catalogs act as service providers); processed ephemerally: **yes** if your server and model don't keep photos; required; purpose: App functionality |
| Other user-generated content (book titles you own) | Collected; not shared; required for recommendations; purpose: App functionality |
| Data encrypted in transit | Yes (release builds only use HTTPS) |
| Users can request deletion | Nothing is stored server-side beyond the 7-day suggestion cache; say so |
| Location, contacts, identifiers, financial, health, messages, app activity | Not collected |
