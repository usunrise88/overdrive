# Zwift Click v2 over BLE: protocol notes

Status: research notes for the Click module (spec §4.7, §9, §10). Written from public sources only, in our own words, with a pointer for every fact. Nothing here was observed on the bench yet; the open questions at the end list what to verify with a real controller. **Stage M4-1 (2026-11-18) implements the plain path of §7 in `core` and tests it against a fake written from these notes: §10 says what is implemented, which vector covers what and which option settles which question.** The encryption of §3.3 and the unlock of §6 are not implemented.

## Sources and tags

Every statement is tagged with one of these:

- **[code: path@tag]**: confirmed in source code. Paths are relative to the repository named.
- **[docs: path]**: stated in a document in the repository named.
- **[inferred]**: my reasoning from the sources, not stated by them.

| Short name | Repository and revision | Licence | Used for |
|---|---|---|---|
| **BC-gpl3** | `OpenBikeControl/bikecontrol`, tag `gpl3`, commit `54b9751` (Jan 2026, app version 4.6.x) | GPL-3.0 | Device recognition, GATT use, button bitmap, handshake as shipped before the unlock changes |
| **BC-HEAD** | same repo, `main` at commit `fb360f6` (app version 7.6.0, 17 Nov 2026) | Non-Commercial licence | Facts only: the later unlock modes and the right-puck-only mode. No code copied. |
| **BC-prop** | `prop_public/` inside BC-HEAD | Non-Commercial licence | Confirmed to be a stub; the real handshake code is in a closed submodule |
| **ZP** | `ajchellew/zwiftplay`, default branch, shallow clone | **none found** (no licence file, no licence statement) | Zwift Play (RC1) BLE layout and its encryption scheme; the only public source for the encryption maths |
| **TC** | `alanburke/trainer-control`, default branch, shallow clone | **none found** | Plain-text handshake and button decoding for Click v1 and v2 |

Important: BC-prop's own header says it is a stub ("contact me if you need the full implementation"). The handshake, the unlock bridge and the keep-awake logic that BikeControl actually runs for Click v2 live in the closed `prop` submodule (`git@github.com:OpenBikeControl/prop.git`). The gpl3 tree pins that submodule at commit `2283bd5` but does not contain its files. [code: BC-gpl3 `.gitmodules`, `prop`; BC-HEAD `prop_public/lib/devices/click_logic.dart`]

## 1. Recognising the device

**Advertising.** A Click v2 is recognised from its manufacturer-specific data and its advertised service, not from its name.

- Company identifier **0x094A** (2378, Zwift Inc.). [code: BC-gpl3 `lib/bluetooth/devices/zwift/constants.dart` `ZWIFT_MANUFACTURER_ID`]
- The first byte of the manufacturer payload gives the device type. **0x0A** = Click v2, right side; **0x0B** = Click v2, left side. BikeControl's own comment marks both values as "unconfirmed". [code: BC-gpl3 `constants.dart`, `ZwiftConstants.CLICK_V2_RIGHT_SIDE` and `CLICK_V2_LEFT_SIDE`]
- Other values for the same byte, for context: 0x09 = Click v1 (BC1); 0x02/0x03 = Play right/left; 0x07/0x08 = Ride right/left. [code: BC-gpl3 `constants.dart`]
- The device advertises a service UUID that is checked before the manufacturer data is read: **0000FC82-0000-1000-8000-00805F9B34FB** (short form `fc82`). [code: BC-gpl3 `lib/bluetooth/devices/bluetooth_device.dart`, `constants.dart`]
- Name-based matching ("Zwift Click") is used only on web builds, because some system APIs do not expose manufacturer data. [code: BC-gpl3 `bluetooth_device.dart`]
- BC-gpl3 creates a Click v2 object only for side 0x0B. The right side is commented out ("see comment above"). The right-side class exists only in BC-HEAD, so the right puck's advertising is confirmed only by the byte values above, and the match on the right side is **inferred** to use the same service UUID. [code: BC-gpl3 `bluetooth_device.dart`; BC-HEAD `lib/bluetooth/devices/zwift/zwift_clickv2_right_side.dart`]

## 2. GATT layout

All of these are inside the custom service **0000FC82-0000-1000-8000-00805F9B34FB**. BikeControl also accepts the older service **00000001-19CA-4651-86E5-FA29DCDD09D1** (used by Click v1 and Play) and looks for it if the newer one is absent. [code: BC-gpl3 `constants.dart`, `zwift_device.dart`]

| Role | UUID | Properties | How BikeControl uses it | Source |
|---|---|---|---|---|
| ASYNC | `00000002-19CA-4651-86E5-FA29DCDD09D1` | notify | Subscribed first. Button frames and vendor/unlock messages arrive here. | BC-gpl3 `zwift_device.dart`; BC-HEAD `zwift_unlock.dart` |
| SYNC_RX | `00000003-19CA-4651-86E5-FA29DCDD09D1` | write, write-without-response | Host writes the handshake, `FF 04 00` and vibration commands here. | BC-gpl3 `zwift_device.dart`, `zwift_ride.dart` |
| SYNC_TX | `00000004-19CA-4651-86E5-FA29DCDD09D1` | indicate (and read) | Subscribed second (indications). Handshake reply arrives here. | BC-gpl3 `zwift_device.dart`; ZP `README.md` |
| (unused by Click v2 in BC-gpl3) | `00000006-19CA-4651-86E5-FA29DCDD09D1` | indicate, read, write | Listed by ZP for Play; not used by any Click path I found | ZP `README.md` |

Standard services also present: Device Information (0x180A, firmware revision 0x2A26) and Battery (0x180F, level 0x2A19). BC-gpl3 defines the firmware-revision UUID as a constant in `lib/bluetooth/ble.dart`; I did not trace where it is read. [code: BC-gpl3 `lib/bluetooth/ble.dart`]

Firmware floor: BC-gpl3 treats Click v2 firmware 1.1.0 as current; BC-HEAD's right-side class uses 1.2.0. [code: BC-gpl3 `zwift_clickv2.dart` `latestFirmwareVersion`; BC-HEAD `zwift_clickv2_right_side.dart`] The spec's "firmware 1.3+ needs unlock" [spec §9] is not present in the code I read; it is **stated in the spec only**.

If the custom service is missing, BikeControl stops and tells the rider to update the controller in the Zwift Companion app. [code: BC-gpl3 `zwift_device.dart`, `handleServices`]

## 3. Connection and handshake

### 3.1 What the public code does for Click v2

Step by step, as BC-gpl3 does it (the version without the later unlock modes):

1. Scan, match section 1.
2. Connect and discover services. Find the custom service (section 2) and its three characteristics. If any is missing, fail.
3. Subscribe to notifications on ASYNC.
4. Subscribe to indications on SYNC_TX.
5. Run the handshake (below). BC-gpl3 runs it **only if the controller counts as unlocked** (section 6).
6. Read the firmware revision (0x2A26) and warn if older than the latest known version.

[code: BC-gpl3 `lib/bluetooth/devices/zwift/zwift_device.dart` `handleServices`; `zwift_clickv2.dart` `setupHandshake`]

The handshake write in BC-gpl3 is the six ASCII bytes **`52 69 64 65 4F 6E`** ("RideOn") written to SYNC_RX without response, with nothing after them. [code: BC-gpl3 `constants.dart` `RIDE_ON`; `zwift_device.dart` `setupHandshake`]

Its reply is a notification or indication on SYNC_TX that starts with "RideOn" followed by two bytes. For Click v2 BC-gpl3 expects **`02 03`**, and it treats everything after that as a public key that it logs and does not use. BC-gpl3 does no key exchange and does not encrypt anything. [code: BC-gpl3 `constants.dart` `RESPONSE_START_CLICK_V2`; `zwift_device.dart` `processDevicePublicKeyResponse`]

Post-handshake write: BC-HEAD writes **`FF 04 00`** to SYNC_RX after "RideOn" on Click v2, but only when the unlock is persisted (or a custom device script exists). BC-gpl3 does not send it. TC sends it on Click v2 after "RideOn" and says "BikeControl sends this to Click v2 after the handshake". [code: BC-HEAD `zwift_unlock.dart` `setupHandshake`; TC `click.js`, handshake block] The bytes' meaning is **unknown**.

### 3.2 Plain or encrypted?

- **TC** sends a plain "RideOn" and says that this "asks for unencrypted messages", then reads plain button frames from both Click v1 and Click v2. [code: TC `click.js`] TC is an unlicensed third-party app; I could not run it, so this is a claim I have not checked.
- **BC-gpl3** uses the same plain handshake and decodes plain button frames for Click v2 (section 4). It sends the handshake only once the controller counts as unlocked, so the plain path is what its releases rely on for the unlocked state. [code: BC-gpl3]
- **Encryption is used by Zwift Play (RC1), per ZP.** ZP's handshake sends "RideOn" + `00 09` + the host's 64-byte public key when its encryption flag is on, and treats a bare "RideOn" reply as "unencrypted mode". [code: ZP `zaplibrary/.../device/common/AbstractZapDevice.kt` `buildHandshakeStart`, `processDevicePublicKeyResponse`] ZP's README also says the Play's handshake reply starts `RideOn 01 01` and that Zwift's request starts `RideOn 01 02`. [docs: ZP `README.md`]
- Whether **Click v2 uses the Play encryption is unknown.** Nothing public confirms it. The evidence (plain works for TC and for BC-gpl3's unlocked state) suggests the Click v2 accepts plain frames at least after unlock. Bench test in section 9.

### 3.3 Encryption as implemented for Zwift Play (ZP). Not confirmed for Click v2.

This is the only public description of the scheme. Treat it as the hypothesis to test, not as the Click spec.

- Key agreement: ECDH on **NIST P-256**. The host's key pair is generated locally. Public keys are 64 bytes, X (32 bytes, unsigned big-endian) followed by Y (32 bytes). [code: ZP `zaplibrary/.../crypto/LocalKeyProvider.kt`, `EncryptionUtils.kt`]
- Shared secret: the raw ECDH result (32 bytes). [code: ZP `EncryptionUtils.kt` `generateSharedSecretBytes`]
- Key derivation: HKDF with SHA-256. Input key material = shared secret. **Salt = device public key (64 bytes) followed by host public key (64 bytes).** Info = empty. Output = 36 bytes. [code: ZP `EncryptionUtils.kt` `generateHKDFBytes`; `ZapCrypto.kt` `generateHmacKeyDerivationFunctionBytes`]
- Split of the 36 bytes: the first 32 bytes are the **AES key**; the last 4 bytes are the **IV prefix**. [code: ZP `ZapCrypto.kt` `initialise`]
- Cipher: **AES-CCM**, 4-byte authentication tag (MAC_LENGTH = 4, i.e. 32 bits). Nonce = the 4-byte IV prefix followed by the 4-byte message counter, so the nonce is 8 bytes. [code: ZP `ZapCrypto.kt` `createNonce`, `encryptDecrypt`; `EncryptionUtils.kt` `MAC_LENGTH`]
- Message counter: starts at 0 and increases by one per encrypted message sent by the host. The counter is written as 4 bytes using Java's default `ByteBuffer` order, which is **big-endian**. [code: ZP `ZapCrypto.kt` `createCounterBytes`]
- Wire format of an encrypted host message: **4-byte counter, then ciphertext, then the 4-byte CCM tag**. [code: ZP `ZapCrypto.kt` `encrypt`]
- Additional authenticated data: none in the Kotlin code. ZP's README says the .NET port treats AAD differently and that the Java code "leaves it on the end of the payload". The README and code disagree or are unclear here, so treat AAD as **unresolved**. [docs: ZP `README.md` "Reverse Engineer"; code: `ZapCrypto.kt`]
- Endianness conflict: ZP's README says the sequence numbers in captured packets are **little-endian** (e.g. `01 00 00 00`), but its code writes them **big-endian**. Unresolved. [docs: ZP `README.md` packet capture section; code: `ZapCrypto.kt`]
- The device's reply to the host's handshake: ZP's code expects the device's 64-byte public key after "RideOn" + `01 03`. [code: ZP `AbstractZapDevice.kt` `processDevicePublicKeyResponse`]

### 3.4 Sequence summary

| # | Direction | Bytes (as known) | Source |
|---|---|---|---|
| 1 | Host subscribes notify on ASYNC | CCCD write | BC-gpl3 `zwift_device.dart` |
| 2 | Host subscribes indicate on SYNC_TX | CCCD write | BC-gpl3 `zwift_device.dart` |
| 3 | Host writes to SYNC_RX | `52 69 64 65 4F 6E` ("RideOn") | BC-gpl3 `zwift_device.dart`; TC |
| 4 | Device replies on SYNC_TX | "RideOn" + `02 03` (Click v2) | BC-gpl3 `constants.dart`; ZP for the Play (`01 03`) |
| 5 | Host writes to SYNC_RX (BC-HEAD only, Click v2) | `FF 04 00` | BC-HEAD `zwift_unlock.dart`; TC |
| 6 | Device sends button frames on ASYNC and/or SYNC_TX | section 4 | BC-gpl3 `zwift_ride.dart`; TC |

Gaps: whether step 4 includes a public key on Click v2 (length unknown); what the device does if step 3 is not sent; whether the device needs anything after step 5.

## 4. Message layout (device to host)

Every notification starts with a **one-byte message type**. The rest is a protobuf-style body (varint fields). Field numbers and names come from the closed `prop` package in BC-gpl3, so only the field numbers that TC or ZP also use are stated here as confirmed.

| Type byte | Decimal | Meaning | Body | Source |
|---|---|---|---|---|
| `0x23` | 35 | **Ride / Click v2 button state** | Field 1, varint: 32-bit bitmap of buttons. **Bit = 0 means pressed, bit = 1 means released.** Field 3, repeated: analog paddle values (used by Ride, not used for Click v2 buttons). | TC `click.js` `parseClickMessage`; BC-gpl3 `constants.dart` `RIDE_NOTIFICATION_MESSAGE_TYPE`, `zwift_ride.dart` `processClickNotification` |
| `0x37` | 55 | Click v1 button state | Field 1 = plus, field 2 = minus, varints; 0 = pressed | TC `click.js`; BC-gpl3 `constants.dart` (comment: "just two varints") |
| `0x15` | 21 | Idle heartbeat (empty body) | none | TC; BC-gpl3 `EMPTY_MESSAGE_TYPE` |
| `0x19` | 25 | Battery | Field 2, varint: percent. Example from TC: `19 10 1F` = 31 %. BC-gpl3 reads byte index 2, which is the same value. | TC; BC-gpl3 `zwift_device.dart` `BATTERY_LEVEL_TYPE` |
| `0x3C` | 60 | Unknown Click v2 type | not known | BC-gpl3 `UNKNOWN_CLICKV2_TYPE`; TC `CLICK_V2_UNKNOWN` |
| `0xFE` | 254 | Disconnect notice, seen when a Zwift Core connects | 1 byte | BC-gpl3 `DISCONNECT_MESSAGE_TYPE`; ZP `ZapConstants` |
| `0x07` | 7 | Play controller frame (not Click v2) | | ZP `ControllerNotification.kt` |

**Button bitmap for Click v2 (type 0x23, field 1).** Bits as used by BC-gpl3, which agree with TC on the shift bits:

| Bit (hex) | Button, left puck | Button, right puck | Source |
|---|---|---|---|
| 0x0001 | Navigation left | | BC-gpl3 `zwift_ride.dart` `RideButtonMask` |
| 0x0002 | Navigation up | | same |
| 0x0004 | Navigation right | | same |
| 0x0008 | Navigation down | | same |
| 0x0010 | | A (right of the diamond) | same; BC-HEAD `zwift_clickv2_right_side.dart` |
| 0x0020 | | B (bottom of the diamond) | same |
| 0x0040 | | Y (top of the diamond) | same |
| 0x0080 | | Z (left of the diamond) | same |
| 0x0100 | Minus (shift up, left) | | same; TC `RIDE_MASK.SHIFT_UP_LEFT` |
| 0x0200 | Minus (shift down, left) | | same; TC `SHIFT_DOWN_LEFT` |
| 0x1000 | | Plus (shift up, right) | same; TC `SHIFT_UP_RIGHT` |
| 0x2000 | | Plus (shift down, right) | same; TC `SHIFT_DOWN_RIGHT` |

Other bits in BC-gpl3's mask table (0x0400 and 0x0800 for left power/on-off, 0x4000 and 0x8000 for right power/on-off) are not on the Click v2 and are not confirmed for it. [code: BC-gpl3 `zwift_ride.dart`]

Physical layout, from BC-HEAD's right-side controller definition: on the right puck, Y is at the top, Z at the left, A at the right, B at the bottom, and Plus sits under B. [code: BC-HEAD `zwift_clickv2_right_side.dart` `controllerLayout`] On the left puck the four navigation buttons form a diamond and Minus sits under "down". [code: BC-HEAD `zwift_clickv2.dart` `controllerLayout`]

**Press and release.** The controller reports the state of all buttons in one bitmap, not one event per press. A press is a bit going from 1 to 0, and a release is the bit going back to 1. TC and BC-gpl3 both turn the bitmap into edges by remembering the last state and ignoring repeats of the same bitmap. [code: TC `click.js` `handle`; BC-gpl3 `zwift_device.dart` `handleButtonsClicked`]

**Neutral frame.** The all-released frame is type `0x23`, field 1 = 0xFFFFFFFF (encoded as `08 FF FF FF FF 0F`). BC-gpl3's emulator sends it on a 5-second timer. [code: BC-gpl3 `zwift_emulator.dart` `_sendKeepAlive`] This is what BikeControl sends **to Zwift or another host when it emulates a controller**; it is not known to be sent to the controller. The type byte `0x23` for the emulator frame is **inferred** from the constant name `CONTROLLER_NOTIFICATION` and the field layout.

## 5. Keep-alive and sleep

- BC-gpl3 has no keep-alive toward the controller. Its emulator sends a neutral frame every 5 seconds to a host that would otherwise drop it. [code: BC-gpl3 `zwift_emulator.dart`]
- BC-HEAD's `controller_keep_alive.dart` is the same idea, for LAN emulators, with a 5-second interval. It is not used for the controller. [code: BC-HEAD `lib/bluetooth/devices/zwift/controller_keep_alive.dart`]
- BC-HEAD's right-puck class has a "keep-awake" loop that keeps the puck awake while connected. Its implementation is `ClickLogic`, in the closed `prop` package. [code: BC-HEAD `zwift_clickv2_right_side.dart`; BC-prop `click_logic.dart` (stub)]
- BC-HEAD changelog 6.5.0 (22-08-2026, line 117) says the right puck "no longer falls asleep after a minute of inactivity, and the LEDs stay on" in right-side-only mode. [docs: BC-HEAD `CHANGELOG.md` line 117]. The mechanism is not public.
- BC-gpl3 troubleshooting says that if the Click v2 has not been connected in Zwift first, BikeControl "will need to reconnect every minute". [docs: BC-gpl3 `TROUBLESHOOTING.md`, "My Click v2 disconnects after a minute"]

The controller-side keep-alive requirement is **unknown** (section 9).

## 6. The unlock requirement

### 6.1 What "locked" means

- A controller "locked" by Zwift hides its Zwift controller service. BC-HEAD records that a controller connected with its service hidden is one that Zwift has locked right now; the buttons are then not readable through that service (**inferred** from this). [code: BC-HEAD `zwift_unlock.dart`, field `unlockServiceHidden` and `handleHiddenUnlockService`]
- Without the service, the BC-gpl3 path fails with "update the firmware in the Companion app". [code: BC-gpl3 `zwift_device.dart`]
- The **device** state that Zwift sets is not visible in any public code. The only stored state is BikeControl's own timestamp: the last successful unlock time, kept per device. [code: BC-gpl3 `zwift_clickv2.dart` `isUnlocked`: unlocked if the last unlock is less than 24 hours old]

### 6.2 Duration

- 24 hours, counted from BikeControl's stored unlock time. [code: BC-gpl3 `zwift_clickv2.dart` `isUnlocked`]
- The device's own timer is **unknown**. BC-HEAD says "re-unlock every 24 hours". [docs: BC-HEAD `CHANGELOG.md`, 6.4.0 entry]
- Whether the device keeps its state across a reboot, or across a pairing change, is unknown.

### 6.3 How the unlock is done (BC-gpl3 and BC-HEAD)

- BikeControl runs an emulated Zwift-side peripheral and mDNS advertisement so that the Zwift app connects to the phone or tablet, sees the controller, and unlocks it. [code: BC-gpl3 `lib/pages/unlock.dart` (`emulator.startServer`), `zwift_emulator.dart`; the bridge logic is in closed `prop`]
- The rider is told to open Zwift (not the Companion) on the same network, press a button on the controller when asked, and wait. Button labels are in the English strings file. [docs: BC-gpl3 `lib/i10n/intl_en.arb`, keys `unlock_*`]
- While this runs, BikeControl captures the controller's **vendor message** on ASYNC: a frame whose first byte is the VENDOR_MESSAGE opcode (numeric value not visible in public code) and whose second byte is `0x03`. It calls this the "vendor challenge" and replays it to Zwift on reconnect. [code: BC-HEAD `zwift_unlock.dart` `processCharacteristic`]
- When Zwift has finished, the unlock state flips and BikeControl stores the time. [code: BC-gpl3 `unlock.dart` `_isConnectedUpdate`; `zwift_clickv2.dart`]

### 6.4 The three modes (BC-HEAD)

From the BC-HEAD changelog and code:

| Mode | Left puck | Right puck | Cost | Where it appears |
|---|---|---|---|---|
| Unlock with Zwift | Unlocked for 24 h through the Zwift app, as in 6.3 | Unlocked along with it | Zwift app, once per day | BC-gpl3 (4.6.x) has it, for everyone that build. BC-HEAD changelog 6.0.0 drops it for Pro users; 6.1.0 makes the new unlock handling available to everyone. |
| Restart (old workaround) | Reboots every few seconds or about a minute; BikeControl sends a RESET | Not locked | RESET writes | BC-HEAD `zwift_clickv2_left_side.dart` comments; BC-gpl3 `zwift_clickv2.dart` `test()` shows `Opcode.RESET` |
| **Right side only** | Dropped from use | **Never locked by Zwift; connects without any unlock** | None | BC-HEAD changelog 6.4.0 (10-08-2026, the setup choice); 7.6.0 (line 8) says "use the right controller without unlocking" |

Sources: [docs: BC-HEAD `CHANGELOG.md` lines for 6.1.0, 6.4.0, 7.6.0]; [code: BC-HEAD `zwift_clickv2_right_side.dart` (comment "Zwift never locked it ... this side's handshake no longer touches the left puck's reset timer")]

**Correction to the spec's premise.** Spec §10 says BikeControl 6.0.0 "reports the right controller working without unlock". The 6.0.0 changelog says something narrower: Pro users no longer have to unlock the controller with Zwift every 24 hours. The right-puck-only mode is first announced in 6.4.0. [docs: BC-HEAD `CHANGELOG.md`, 6.0.0 and 6.4.0 entries]

### 6.5 Answer to the key question

**Is the no-unlock method in the GPL-3.0 tree?** No. BC-gpl3 has no right-puck class, its right-side type is commented out, and its handshake runs only when unlocked. [code: BC-gpl3 `bluetooth_device.dart`, `zwift_clickv2.dart`]

**Is it only in the later Non-Commercial code?** The *feature* is in BC-HEAD (the class, settings, onboarding, changelog 6.4.0 onward). The *handshake that makes the right puck work without unlock* is not in any public code: `ClickLogic.setupHandshake(..., isRight: true)` is a stub in BC-prop, and the real body is in the closed `prop` package. [code: BC-HEAD `zwift_clickv2_right_side.dart` line calling `ClickLogic.setupHandshake`; BC-prop `click_logic.dart`]

**So the verdict is:** present in later Non-Commercial code as a state machine and UI; its radio-level part is in a closed package; not in the GPL-3.0 tree. Any port of BC-HEAD code is barred by the Non-Commercial licence (see `docs/references-licences.md`). The ability to connect the right puck without unlocking is therefore **not public knowledge** in a form we can use. We must establish it with the bench test in section 9, from our own captures.

## 7. Can we implement from these notes?

Partly.

**Specified well enough to implement (confirmed by two independent sources, one of which is licensed GPL-3.0):**

- Discovery by manufacturer data and service UUID (section 1).
- GATT roles and UUIDs for the custom service (section 2).
- Plain "RideOn" write and the reply prefix (section 3.1), subject to the bench test in section 9.
- Button bitmap, bit meanings, edge detection (section 4).

**Not specified well enough (the notes do not settle these):**

- Whether a Click v2 needs the encrypted handshake (section 3.2).
- The unlock state machine, including the device-side timer (section 6).
- The right-puck-only handshake, which is in closed code (section 6.5).
- The meaning of `FF 04 00`, of the RESET opcode, and of message type `0x3C` (sections 3.1, 4, 6.4).
- Keep-alive or sleep behaviour toward the controller (section 5).

The Click module can start with the plain path on the right puck and the bitmap in section 4. Unlock support should wait for the bench results. (M4-1 did exactly that: section 10.)

## 8. Clean-room note

I read BC-gpl3 and BC-HEAD for facts and wrote these notes in my own words. The lists of UUIDs and bit masks are facts that the protocol requires and are restated here with their sources. Nothing was copied from code. The ZP encryption section restates the scheme in prose; the implementation must be written from these notes and tested against captures, not ported from ZP or BC.

## 9. Open questions for the bench test

Equipment: one Click v2 (left and right puck, both if available), an Android phone with **Developer options → Enable Bluetooth HCI snoop log** turned on, `adb` to pull the log (`adb bugreport` or the `btsnoop_hci.log` file), and Wireshark to read it. Record firmware versions from the Companion app and the Zwift app version before each run.

1. **Advertising.** Which side does manufacturer byte 0x0A and which does 0x0B? Scan both pucks and record the full manufacturer payload and the advertised services.
2. **Plain handshake.** On a fresh right puck with no Zwift connection in the last day, write `RideOn` and nothing else. Does it answer with "RideOn" + `02 03`? Record the exact reply and its length. Does it send buttons without an unlock?
3. **Public key in the reply.** Is there a 64-byte key after the reply prefix? If so, does the key change between connections?
4. **Encryption.** Compare the frames after a successful Zwift unlock with the plain frames. Look for a 4-byte counter prefix and a trailing 4-byte tag. If the frames are plain, this section is closed.
5. **Bit meanings.** Press each of the five buttons on each puck in turn and record the bitmap. Confirm 0 = pressed and the mask values in section 4, including the plus button on the right puck.
6. **`FF 04 00`.** Send it after the handshake with and without the earlier steps. Record any reply. Does it change behaviour?
7. **Keep-alive.** Stay connected and idle (pedalling, no buttons). Measure how long before a disconnect or a sleep. Try sending the neutral frame to the controller and see whether that changes anything. Repeat with a 60-second and a 5-minute gap.
8. **Unlock duration on the device.** Unlock with Zwift, then check the service after 1 h, 12 h, 25 h and after a phone reboot. Note whether the controller hides its service again and when.
9. **Left-puck reset.** Use BikeControl's debug "Reset now" or equivalent to send RESET (BC-gpl3, `zwift_clickv2.dart` `test()`). Record the bytes written to SYNC_RX. Record the opcode value; the closed code does not show it.
10. **Message `0x3C`.** Log when it appears and what follows it.
11. **Battery.** Confirm that `0x19` field 2 is the percentage across two pucks.
12. **Service visibility.** Does the custom service appear in GATT discovery on a right puck that has never been unlocked? Does it appear on a left puck that has? Record the advertised service list each time.
13. **Endianness and AAD.** Only if item 4 finds encryption: check the counter byte order (ZP README says little-endian, ZP code says big-endian) and whether the CCM tag verifies with and without AAD.
14. **Right-puck state after a restart of the controller.** Power-cycle the puck and repeat items 2 and 5. Does a right puck need Zwift at all after it has once been paired?

Each result should be written back into this file with the date, the firmware version and the snoop-log file name (kept out of the repository, because raw recordings must not be committed).

## 10. What is implemented (stage M4-1), and what covers it

The plain path on the right puck, in `core` (`app.overdrive.core.click`, `app.overdrive.core.ble.ClickUuids`), from sections 1 to 7 of these notes and nothing else; the decisions and the table of every flippable choice are in `docs/adr/0011-click-gestures-and-sync-stub.md`. **A green test proves consistency with these notes, not with a Click.**

| Section | Implemented as | Not implemented |
|---|---|---|
| §1 recognising | `ClickAdvertisements.recognise` / `scanFilter`: service `FC82` (the older one as fallback), manufacturer `0x094A`, first byte `0x0A` right or `0x0B` left; never the name | |
| §2 GATT layout | `ClickUuids`; discovery looks for the three characteristics in `FC82`, else in the older service | `00000006-…` (Play only); Device Information firmware (`0x2A26`) is read once and kept out of recordings |
| §3.1 handshake | `ClickDevice` set-up: ASYNC notify, SYNC_TX indicate, `RideOn` without response, the reply, `FF 04 00` | |
| §3.2, §3.3 encryption | not implemented; frames are read as plain bytes | the whole key agreement and cipher; an encrypted Click would show as a rejected reply or unrecognised frames |
| §4 messages | `ClickCodec.parse`: `0x23`, `0x15`, `0x19`, `0xFE`, everything else (`0x3C` included) as `Unrecognised`; `ClickBits`; `ButtonEdges` | `0x37` (Click v1) and `0x07` (Play) are reported as unrecognised |
| §5 keep-alive | none by default; `ClickOptions.keepAliveInterval` and `keepAliveFrame` (the neutral frame) | |
| §6.1 locked means hidden | `ClickState.NeedsUnlock(SERVICE_HIDDEN)`; and `NeedsUnlock(NO_EVENTS)` for a controller that answers and then says nothing | the unlock itself (§6.3, §6.4), the RESET opcode and the left puck's restart workaround |

**Vectors.** The ones the code is tested against are synthetic, derived by hand from the notes, never captured: **CK-V1** the neutral frame `23 08 FF FF FF FF 0F` (§4); **CK-V2 to CK-V7** one pressed button each, by the varint rule of the §4 table: A `EF FF FF FF 0F`, B `DF FF FF FF 0F`, Y `BF FF FF FF 0F`, Z `FF FE FF FF 0F`, Plus `FF DF FF FF 0F`, Plus's second bit `FF BF FF FF 0F` (each after `23 08`); **CK-V8** A and Plus together `EF DF FF FF 0F`; **CK-V9** battery `19 10 1F` = 31 % (§4); **CK-V10** heartbeat `15`; **CK-V11** `3C 01 02` unrecognised; **CK-V12** `FE`; **CK-V13** the handshake `52 69 64 65 4F 6E`; **CK-V14** the reply `RideOn 02 03`, with and without a 64-byte tail; **CK-V15** `FF 04 00` (all in `ClickCodecTest`). Beyond them: fields in another order and unknown fields skipped, a ten-byte varint, every wire type, every proper prefix, random bytes of every short length and an encode-and-decode round trip, none of which may throw.

**Wired into the app (stage M4-2, ADR 0012).** The same code runs in the ride app over the Android transport: the Click is paired from the Devices scan, owned by the container, driven through the one routing rule, and its options are switches in Settings, Developer · Click. **Each question below has a step in `docs/bench-checklist.md`, session 5** (the coverage table there maps CK 1 to CK 14); the app's Bluetooth session files (`...-click.txt`) are the recordings. Nothing was observed on a Click yet.

**Which option settles which question of section 9** (the names are `ClickOptions` unless noted):

| Question | Option or constant |
|---|---|
| 1 advertising, which byte is which side | `rightSideByte`, `leftSideByte`, `acceptedSides` |
| 2 plain handshake, the reply | `handshakeReply`, `handshakeTimeout`, `writeWithResponse` |
| 3 a public key in the reply | `requestedMtu`; the length is in `ClickEvent.HandshakeAnswered` and in a recording (the key itself is zeroed, never written) |
| 4 encryption | none: look at the rejected replies and `UnrecognisedFrame`/`MalformedFrame` events |
| 5 bit meanings | `ClickBits`, `ignoredBits`; the app's *Learn buttons* (`ButtonLearner`) |
| 6 `FF 04 00` | `sendPostHandshake` |
| 7 keep-alive | `keepAliveInterval`, `keepAliveFrame`, `noEventTimeout` |
| 8 unlock duration | none: observe `ClickState` over time |
| 10 type `0x3C` | `ClickEvent.UnrecognisedFrame` (type and length) |
| 11 battery | `ClickDevice.battery` |
| 12 service visibility | `requireAdvertisedService`, `serviceHiddenMeansLocked`, `acceptLegacyService` |
| 14 a right puck after a restart | `ClickState` after a power cycle; `silentMeansLocked` |
| 9 RESET, 13 endianness and AAD | not implemented |

**Recordings.** A Click session recorded through the existing decorator keeps no address and no unit identity: the firmware revision is `*`, and the tail after `RideOn` and the version bytes of a handshake reply becomes zeros of the same length (a key that never changes would name the unit; the length is what question 3 asks). Unknown vendor frames are written as they are, because question 10 needs them; keep recordings out of the repository.
