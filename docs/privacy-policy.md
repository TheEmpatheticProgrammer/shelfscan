---
title: ShelfScan privacy policy
---

# ShelfScan privacy policy

_Last updated: 17 November 2026_

ShelfScan ("the app") helps you catalog the physical books you own. This policy explains what the app keeps on
your phone, what it sends off it, and where that goes.

## What stays on your phone
Your library (titles, authors, covers, reading status, and the synopses the app saves) and your Want to read list are
stored **only on your phone**. They are included in your phone's Android backup if you have backup turned on, and in
any export file you choose to create.

## What is sent, and why
The app works by sending some information to the ShelfScan server, which is run by the app's developer:

- **Photos you take or choose for scanning.** Sent to the ShelfScan server, which passes them to an AI model to read
  the book titles. The AI model runs on the developer's own computer; photos are **not** sent to any third-party AI
  service. Neither the server nor the model stores your photos: each photo is processed and then discarded. If the
  server can't be reached, photos wait on your phone until they can be sent, and are deleted from your phone after
  you review the result or discard the scan.
- **Book titles and authors** from your library and Want to read list, when you ask for recommendations, similar
  books or upcoming releases. Sent to the server and the AI model to produce suggestions. The server may keep the
  resulting suggestions for up to 7 days so repeated requests are fast. They're stored under a one-way hash of the
  book list, not your library itself.
- **What you type into search, and scanned barcodes (ISBNs).** Sent to the server to look the book up.

To look up book details and covers, the server queries **Google Books** and **Open Library** with book titles,
authors or ISBNs. These requests come from the server, not your phone, and contain nothing that identifies you. Your
phone loads cover images directly from those services' image addresses, as any web page would.

All traffic between the app and the server is encrypted (HTTPS).

## What ShelfScan does not do
No accounts, no ads, no analytics or tracking SDKs, and no selling or sharing of your data. The app asks for no
camera permission: photos come from your camera app or photo picker, and barcodes are read on your phone by Google
Play services' code scanner.

## Server logs
The server keeps technical logs (request times, errors, and the titles it read from a photo) to keep the service
working. Logs are deleted after **30 days**.

## Your choices
- Delete books from the app, or uninstall it, to remove your library from your phone.
- Nothing about you is stored on the server beyond the logs and 7-day suggestion cache described above, and none of
  it is linked to an account or to your identity. If you'd still like anything removed, email the address below.

## Children
ShelfScan isn't directed at children under 13.

## Changes
If this policy changes, the new version will be posted at this address with a new "Last updated" date.

## Contact
Questions or requests: [mongitox@gmail.com](mailto:mongitox@gmail.com)
