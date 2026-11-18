# Prior-art projects: licences and what they mean for Overdrive

Status: research note, input to the licence decision for the public repository. The owner decides the licence; this note recommends one (section 5). Overdrive has **no LICENSE file yet**, so by default all rights are reserved to its authors and nobody may copy from it.

Method: each SPDX identifier was taken from the project's `LICENSE` file at its default branch, fetched from `raw.githubusercontent.com` on the date of this note, unless marked otherwise. Where the spec names a licence but no file was checked, that is said. Source tree and revisions are in `docs/protocols/click-v2.md`.

## 1. Table

| Project | Repository | Licence (from its LICENSE file) | What matters to us | Consequence for the Overdrive repo |
|---|---|---|---|---|
| **BikeControl**, tag `gpl3` and earlier | `OpenBikeControl/bikecontrol` | **GPL-3.0** (full GPL v3 text at tag `gpl3`) | Click v2 device recognition, GATT roles, button bitmap, plain RideOn handshake as shipped in 4.6.x. | Copying or porting code from it requires Overdrive to be GPL-compatible. Reading it for facts is fine. |
| **BikeControl**, after tag `gpl3` (HEAD, 7.6.0) | same | **Non-Commercial Software Licence Agreement v1.0**, © 2026 OpenBikeControl UG (haftungsbeschränkt). Its notice says versions before `gpl3` stay GPL-3.0. | The right-puck-only mode and the later unlock modes. The handshake for it is in a closed submodule (`prop`). | HEAD code cannot be ported or copied into a public repo. Facts from it are used only as described in the protocol notes. I read only the opening of the licence file; the redistribution clause is as the brief summarises it (source-only, same licence). |
| **zwiftplay** | `ajchellew/zwiftplay` | **None found.** No LICENSE file on the default branch and no licence statement in the README. | Zwift Play (RC1) BLE layout and an encryption scheme (P-256 ECDH, HKDF-SHA256, AES-CCM). The only public description of that scheme. | Without a licence, the code is under default copyright. **Do not copy or port it.** Using facts from it (as written in the protocol notes) and implementing from our own captures is the approach to take. Ask the author for a licence if we ever want more. |
| **trainer-control** | `alanburke/trainer-control` | **None found.** No LICENSE file; `package.json` has no licence field; raw LICENSE returns 404 on `main` and `master`. | Plain RideOn handshake for Click v1 and v2, the `FF 04 00` follow-up, bitmap decoding. | Same as zwiftplay: no licence, facts only, no copying. |
| **OpenTrainer** | `janpetr93/OpenTrainer` | **MIT** (© 2026 Jan Petr) | MCP bridge for training planning. Not needed for the Click module. | Permissive. Code could be used with the MIT notice kept. |
| **openspin** | `yaportmax/openspin` | **MIT** (© 2026 Just Watts contributors) | Trainer app with MCP coaching and Strava upload. | Permissive, as above. |
| **qdomyos-zwift (QZ)** | `cagnulein/qdomyos-zwift` | **GPL-3.0** (GPL v3 text). The file alone does not say "only" or "or later". Not cloned (large repository); read via raw URL. | Reference FTMS and virtual gearing. | Copying requires GPL compatibility. Reading for facts is fine. |
| **SHIFTR** | `doudar/SHIFTR` | **GPL-3.0** (GPL v3 text) | Reverse-engineered Zwift trainer-side shifting. | As QZ. |
| **Indoor Bike** | Repository not given in the spec; the app's site is `indoorbike.app` | Not verified against a LICENSE file. The site says "Open source, GPLv3". | Kotlin Multiplatform trainer app with virtual gears and intervals.icu sync. | Treat as GPL-3.0 until the repository is identified and its LICENSE file is read. |
| **Ewoc** | `Ewoc2026/ewoc` | **GPL-3.0** (GPL v3 text). The spec says "or later"; the file does not show it. | Native Android FTMS workout app. | As QZ. |
| **OpenBike** | `HugoLeBoennec/OpenBike` | **Apache-2.0** (matches the spec) | Flutter trainer app with a layered architecture. | Permissive. Apache-2.0 code can be used with its notice and NOTICE file kept. |
| **intervals-icu-health-sync** | `daktak/intervals-icu-health-sync` | **AGPL-3.0**, not GPLv3 as the spec says | Health Connect to intervals.icu wellness sync. | **Spec mismatch: correct §10 to AGPL-3.0.** AGPL adds a network clause, so it is stricter than GPL for any server use. Reading for facts is fine; copying needs AGPL compatibility. |
| **intervals-mcp-server** | `mvilanova/intervals-mcp-server` | **GPL-3.0** (GPL v3 text). The spec does not state a licence. | Existing MCP server over the intervals.icu API. | Add the licence to the spec's table. As QZ for copying. |

Not on the brief's list but in the spec's table: `opw2-probe` (diagnostic app for a watch). Not checked.

## 2. Facts versus copying

The clean-room rule for this project is: read other projects for **facts** (UUIDs, message layouts, sequences, licence names), write our own code from those facts, and keep a pointer to each fact. Under that rule:

- Reading BikeControl (any tag), QZ, SHIFTR, Ewoc or zwiftplay is allowed. The protocol notes are written in our own words.
- Re-implementing a protocol from facts is not copying, and no licence in this table prohibits it. This is the normal position in interoperability work. A licence cannot protect a fact such as a UUID, but it can protect the code that states it. I am not a lawyer; an owner who wants certainty should ask one.
- Copying or closely translating **code** from a GPL project means our repository must be GPL-compatible. From the Non-Commercial BikeControl HEAD, no copying is allowed at all, in any repository that is public.

## 3. Assets and dependencies

- **Geist (SIL Open Font License 1.1).** Free to bundle in an app, including a closed or GPL app. Two conditions: the font itself may not be sold on its own, and the licence text must ship with the font, plus any Reserved Font Name rules. OFL-1.1 is not an MIT-style licence. **Compatibility with GPL-3.0 is not something I could verify here.** My recollection is that the FSF does not list OFL-1.1 as GPL-compatible; check this before choosing GPL.
- **Lucide (ISC).** A permissive licence functionally equal to MIT. Compatible with GPL-3.0, MIT and Apache-2.0. Keep the copyright notice with the icons.
- **Garmin FIT SDK.** See section 4. The licence is **not** an open-source licence and it restricts distribution.

## 4. Garmin FIT SDK licence

Source: the licence file in the npm package `@garmin/fitsdk` version 21.222.0 (`LICENSE.txt`, "Last updated: October 12, 2022"). Its title is "Flexible and Interoperable Data Transfer (FIT) Protocol License Agreement". The package's `package.json` says "SEE LICENSE IN LICENSE.txt". I did not check other language SDKs (Java, C#) from Garmin; their terms may differ.

What it says that matters to us:

- **§1 Grant.** A non-exclusive, royalty-free, non-transferable, non-sublicensable licence "for Licensee's internal business purposes, including to use the FIT protocol in any software created by Licensee", plus the right to make modifications.
- **§2(c).** No renting, selling, sublicensing, distributing, publishing or otherwise making the technology available to any third party, "except as set forth herein". The grant does not expressly include distribution.
- **§2(d).** No distributing the technology "so that any part of it becomes subject to any license that requires that the Licensed Technology ... be disclosed or distributed in source code form, or that others have the right to modify it". **This is the GPL trigger.** Shipping the SDK inside an APK distributed under GPL-3.0 would breach it. Under Apache-2.0 or MIT, §2(c) still forbids distribution without permission.
- **§2(f).** No use for benchmarking or competitive analysis of the technology.
- **§2(h)** and **§3.** Must comply with the FIT protocol definitions and keep interoperability.
- **"Licensed Technology"** is defined as the SDK "that includes documentation describing the FIT protocol and related source code files". So reading the FIT protocol documentation may itself fall under the agreement, which says that using it accepts the terms. This is a point for the lawyer.
- **§9** termination on breach; **§6** no warranty; **§8** liability capped at $100; governed by Kansas law.

Consequence: the SDK **must not be bundled** in the public APK unless Garmin gives written permission. Options, for the owner to decide:

1. Write the FIT encoder ourselves from Garmin's published protocol documentation. This is the usual route for open-source FIT writers, and it fits §1's "use the FIT protocol in any software created by Licensee". Get a view on whether reading the documentation brings us under the agreement (see the definition above).
2. Ask Garmin for a written licence that allows distribution, if the SDK is needed unchanged.

FIT export (spec §4.8) does not by itself force a particular Overdrive licence. Option 2 would replace these terms with whatever Garmin grants in writing.

## 5. Recommendation for the Overdrive licence

The owner decides. Three options were compared.

| Option | For | Against |
|---|---|---|
| **GPL-3.0-or-later** | Lets us copy code from QZ, SHIFTR, Ewoc, intervals-mcp-server and Indoor Bike. Matches most of the trainer-app ecosystem. | Clashes with the FIT SDK's §2(d), so the SDK cannot be bundled either way. Compatibility of OFL-1.1 (Geist) with GPL-3.0 is unverified (section 3). Copying GPL code would conflict with the clean-room rule the project has chosen. |
| **MIT** | Simple, widely understood, compatible with everything in this table except copying from GPL and AGPL code. | No express patent grant. Copying MIT or Apache code is easy, but copying GPL code is not possible without relicensing. |
| **Apache-2.0** | Express patent grant. Compatible with OpenBike (Apache-2.0), OpenTrainer and openspin (MIT, via notice), Lucide (ISC) and the Android libraries. Code from it can go into a GPL project later. | Copying GPL code into an Apache-2.0 repository would make the combined work GPL, which is incompatible with the Apache licence's distribution terms, so it is not possible. Same as MIT for this purpose. |

**Recommendation: Apache-2.0**, with the clean-room rule kept in `CLAUDE.md` (facts only from GPL, unlicensed and Non-Commercial sources). Reasons:

1. The project already forbids copying from the GPL, unlicensed and Non-Commercial sources, so GPL's one practical advantage (being allowed to copy them) is not in use.
2. The FIT SDK's §2(c) and §2(d) block distribution in any case. The clash with GPL-3.0 adds a second reason to avoid it.
3. Apache-2.0 has an express patent grant and fits the MIT and Apache sources we would most likely read or reuse, and the ISC and OFL assets.
4. Changing the licence later is possible while the owner is the sole author. It becomes hard once outside contributions exist, so the choice should be made now.

Before adopting any option: the owner should check the OFL-1.1 point (section 3) and whether the FIT documentation route in section 4 is acceptable. Record the decision as an ADR in `docs/adr/` and add the LICENSE file then (this note did not add one).

## 6. Open items

- Identify Indoor Bike's repository (not linked from the spec) and read its LICENSE file.
- Ask the zwiftplay and trainer-control authors for licences if they are to be reused beyond reading.
- Correct the spec table: intervals-icu-health-sync is AGPL-3.0; intervals-mcp-server is GPL-3.0; BikeControl needs both regimes spelled out (GPL-3.0 up to `gpl3`, Non-Commercial after).
- Lawyer review of section 4 (the FIT SDK) and of the clean-room position in general.
