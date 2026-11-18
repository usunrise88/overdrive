# FTMS and Heart Rate Service over BLE: codec notes

Status: research notes for the pure-Kotlin FTMS and Heart Rate codecs (spec §2, §4.1, §4.3–§4.5, §7.5). Written from the Bluetooth SIG GATT definitions and two independent open-source decoders, not from memory. Nothing here was observed on the bench. The open questions at the end list what to check with the Van Rysel D500 and the Coospo HR7.

**Main gap:** the Bluetooth SIG Fitness Machine Service 1.0 and Heart Rate Service 1.0 specification texts were **not retrieved**. The bluetooth.com spec pages load, but their PDF download endpoints (`files.bluetooth.com/download/...`) return HTML landing pages, not files. Everything below marked *spec* therefore comes from the SIG's GATT characteristic XML (2017, see table), and everything about FTMS Control Point, Fitness Machine Status and Fitness Machine Feature bit meanings is either *cross-checked* (two decoders agree) or *single-source* (one decoder only) or *unverified*.

## Sources and tags

| Tag | Source | Licence / status | Used for |
|---|---|---|---|
| **[SIG-XML]** | Bluetooth SIG GATT characteristic definitions, mirrored at `raw.githubusercontent.com/oesmith/gatt-xml/master/` (files `org.bluetooth.characteristic.indoor_bike_data.xml`, `...heart_rate_measurement.xml`; the FTMS Feature, Control Point and Status files are stubs that only give UUIDs). Copyright 2017 Bluetooth SIG, last-modified 2017-02-14. | SIG copyright notice in each file; facts only, nothing copied | Indoor Bike Data and Heart Rate Measurement layouts, UUIDs |
| **[SIG-svc]** | SIG Service definitions, same mirror: `org.bluetooth.service.fitness_machine.xml` (UUID 1826), `org.bluetooth.service.heart_rate.xml` (UUID 180D) | same | Service UUIDs, mandatory characteristics and properties (write with response, indicate, notify) |
| **[Nordic]** | `NordicSemiconductor/bluetooth-numbers-database`, `v1/characteristic_uuids.json` and `v1/descriptor_uuids.json`, `master` | Licence not checked in this session. UUID facts only. | Cross-check of 16-bit UUIDs |
| **[pycycling]** | `pycycling` 0.4.1 from PyPI, modules `pycycling/ftms_parsers/*.py` and `pycycling/heart_rate_service.py` | **MIT** (checked in package METADATA) | Second decoder for every byte layout; verifier for the vectors in §8 |
| **[Ewoc]** | `github.com/Ewoc2026/ewoc`, commit `e11a320d0e9b6f61b3d4329e43ef92e97e433753`, files `app/src/main/java/io/github/ewoc2026/ewoc/ftms/IndoorBikeParser.kt`, `.../ble/FtmsBleClient.kt`, `.../ble/FtmsController.kt` | **GPL v3** (LICENSE file). Read for facts only, no code copied. | Kotlin Android cross-check of Indoor Bike Data, Control Point opcodes and write behaviour. **Vendor-specific parser:** its header says "Tunturi-specific mapping", and it departs from the SIG layout (see §2.4). |
| **[Android]** | developer.android.com pages fetched 2026-11-18: `develop/connectivity/bluetooth/bt-permissions`, `reference/android/bluetooth/BluetoothGatt`, `reference/android/bluetooth/BluetoothDevice` | Google documentation | §7 only |
| **[FTMS-PDF] / [HRS-PDF]** | SIG spec PDFs | **Not retrieved** | Cannot be used as a source yet |
| *unverified* | Stated without a source in this session | | Treat as a design assumption |

Tag rule: a fact is marked **[spec]** (SIG-XML or SIG-svc), **[x-checked: pycycling, Ewoc]** or **[x-checked: pycycling]** (single decoder), or **[unverified]**.

## 1. UUIDs

| Item | UUID | Source |
|---|---|---|
| Fitness Machine Service (FTMS) | `0x1826` (`00001826-0000-1000-8000-00805F9B34FB`) | [SIG-svc] [Ewoc] |
| Indoor Bike Data | `0x2AD2` | [SIG-XML] [Nordic] [Ewoc] |
| Fitness Machine Feature | `0x2ACC` | [SIG-XML stub] [Nordic] |
| Fitness Machine Control Point | `0x2AD9` | [SIG-XML stub] [Nordic] [Ewoc] |
| Fitness Machine Status | `0x2ADA` | [SIG-XML stub] [Nordic] |
| Training Status | `0x2AD3` (read and notify, optional) | [Nordic] [SIG-svc] |
| Supported Speed Range / Inclination Range / Resistance Level Range / Power Range | `0x2AD4` / `0x2AD5` / `0x2AD6` / `0x2AD8` (read, mandatory when the matching target setting is supported) | [Nordic] [SIG-svc] |
| Heart Rate Service | `0x180D` | [SIG-svc] |
| Heart Rate Measurement | `0x2A37` (notify, mandatory) | [SIG-XML] [SIG-svc] [Nordic] |
| Body Sensor Location | `0x2A38` (read, optional) | [SIG-svc] [Nordic] |
| Client Characteristic Configuration (CCCD) | `0x2902` | [Nordic] [SIG-svc] |
| Battery Service | `0x180F` | **[unverified]**: not in the fetched data |
| Battery Level | `0x2A19` | [Nordic] |

Properties from [SIG-svc] for the FTMS service: Fitness Machine Feature is read (mandatory); Indoor Bike Data is optional as a characteristic, and notify is mandatory when it is present; Control Point is **write with response** (`WriteWithoutResponse` excluded) and **indicate** (mandatory); Fitness Machine Status is notify (mandatory when the Control Point is supported); Supported Power Range is read (mandatory when power target setting is supported).

## 2. Indoor Bike Data (0x2AD2)

Notification payload = 16-bit little-endian **flags**, then the fields that the flags announce, in the order of §2.2. All multi-byte integers are little-endian. [SIG-XML: Note in the file: fields are LSO to MSO.]

### 2.1 Flags (16 bits) [SIG-XML], bit meanings as listed by the SIG XML

| Bit | Name in SIG XML | Meaning when 1 | Field size if present |
|---|---|---|---|
| 0 | **More Data** (inverted: see below) | "More Data" = 1 means the Instantaneous Speed field is **absent** | — |
| 1 | labelled "Instantaneous Cadence present" in XML | see §2.4, bits 1–2 disputed | |
| 2 | labelled "Average Speed present" in XML | see §2.4, bits 1–2 disputed | |
| 3 | Average Cadence present | | 2 |
| 4 | Total Distance present | | 3 |
| 5 | Resistance Level present | | 2 (sint16) |
| 6 | Instantaneous Power present | | 2 (sint16) |
| 7 | Average Power present | | 2 (sint16) |
| 8 | Expended Energy present | triple: total energy, energy per hour, energy per minute | 2 + 2 + 1 |
| 9 | Heart Rate present | | 1 |
| 10 | Metabolic Equivalent present | | 1 |
| 11 | Elapsed Time present | | 2 |
| 12 | Remaining Time present | | 2 |
| 13–15 | Reserved for future use | ignore | — |

**Bit 0 is inverted.** Its enumeration in [SIG-XML] is `0 = False` with `requires="C1"` (Instantaneous Speed), and `1 = True`. So **Instantaneous Speed is present when bit 0 is 0**. Both [pycycling] (`if flag_more_data == 0: instant_speed = ...`) and [Ewoc] (always reads speed; ignores bit 0) agree that the field is present when bit 0 is 0. Ewoc does not implement the absence case, so only pycycling's behaviour is cross-checked for bit 0 = 1.

### 2.2 Field order and encoding

After the flags, fields appear in this order. Each row is size, signedness, unit and resolution. The resolutions come from the `DecimalExponent` / `BinaryExponent` and `Unit` entries in [SIG-XML].

| # | Field | Flag bit | Format | Unit and resolution | Decode |
|---|---|---|---|---|---|
| 1 | Instantaneous Speed | 0 = 0 | uint16 | km/h, 0.01 (`DecimalExponent -2`) | raw / 100 |
| 2 | Average Speed | bit 2 (XML) / bit 1 (decoders), §2.4 | uint16 | km/h, 0.01 | raw / 100 |
| 3 | Instantaneous Cadence | bit 1 (XML) / bit 2 (decoders), §2.4 | uint16 | 1/min, 0.5 (`BinaryExponent -1`) | raw / 2 |
| 4 | Average Cadence | bit 3 | uint16 | 1/min, 0.5 | raw / 2 |
| 5 | Total Distance | bit 4 | **uint24** | metres, 1 | raw |
| 6 | Resistance Level | bit 5 | **sint16** | unitless, 1 | raw |
| 7 | Instantaneous Power | bit 6 | **sint16** | watts, 1 | raw |
| 8 | Average Power | bit 7 | **sint16** | watts, 1 | raw |
| 9 | Total Energy | bit 8 | uint16 | kcal, 1 | raw |
| 10 | Energy Per Hour | bit 8 | uint16 | kcal/h, 1 | raw |
| 11 | Energy Per Minute | bit 8 | uint8 | kcal/min, 1 | raw |
| 12 | Heart Rate | bit 9 | uint8 | bpm, 1 | raw |
| 13 | Metabolic Equivalent | bit 10 | uint8 | METs, 0.1 (`DecimalExponent -1`) | raw / 10 |
| 14 | Elapsed Time | bit 11 | uint16 | s, 1 | raw |
| 15 | Remaining Time | bit 12 | uint16 | s, 1 | raw |

Sources: [SIG-XML] for all rows; [pycycling] `ftms_parsers/indoor_bike_data.py` for the same order, sizes and signedness (cross-checked). [Ewoc] reads rows 1–15 in the same order when its own flag mapping is ignored (see §2.4).

The XML's "Energy" unit labels say `kilogram_calorie`, so the expended-energy triple is in kcal (matching pycycling's naming). Note that the **HR-Measurement** energy field is in kJ (§6); the two are not the same unit.

### 2.3 Parsing a notification, step by step

1. Require at least 2 bytes. Read flags = u16 LE from bytes 0–1. Offset = 2.
2. If bit 0 == 0: read Instantaneous Speed at offset (u16 LE, /100). Offset += 2.
3. For each of bits 1–12 in the order of §2.2 (using the bit for each row): if set, read the field at the current offset with the size in the table, then advance the offset by that size. The expended-energy triple reads 5 bytes at once (u16, u16, u8).
4. Bits 13–15 are reserved: ignore them. Do not treat them as having fields.
5. If the payload ends before a field announced by a set bit, the notification is **malformed** (treat it as a parse failure, not a partial record). [Ewoc] does this: `require(n)` throws, and it emits a parse failure record with the flags and the first 8 bytes. [unverified in spec text; this is Ewoc's policy.]
6. Trailing bytes after the last announced field: pycycling ignores them. Ewoc ignores them too (it does not check the length at the end).

**Field absent rules.** A field is absent only when its flag bit is 0. A field is never inferred from the length. The only exception is bit 0: if it is 1, the Instantaneous Speed field is absent even though it sits first in the list.

**Split across notifications.** The XML names bit 0 "More Data", which suggests a record may continue in a later notification. **Unverified:** the retrieved XML does not say how a continuation is formed, and neither decoder reassembles notifications. Both parse each notification as a full record. Until the bench shows splits, the codec treats each notification as a complete record and logs any with bit 0 set.

### 2.4 Disputed: flag bits 1 and 2 **[high risk]**

- [SIG-XML] labels **bit 1 = Instantaneous Cadence** and **bit 2 = Average Speed**. Its own field list puts Instantaneous Speed, then Average Speed, then Instantaneous Cadence, so the XML's bit labels contradict its own field order.
- [pycycling] and [Ewoc] both read **bit 1 = Average Speed** and **bit 2 = Instantaneous Cadence**. Two independent decoders agree with each other and with the XML's field order.
- [pycycling] has a comment saying the SIG spec reverses the cadence bit ("0 means present"). The SIG XML does not say that (`Enumeration key="1"` = present), so that comment is not supported by the XML. The two decoders' reading is the one we use.

**Decision for the codec:** bit 1 = Average Speed, bit 2 = Instantaneous Cadence, marked as *x-checked, disputed by one XML label set*. The bench test on the D500 must confirm it with a real cadence sample (§9).

### 2.5 Ewoc's variant: do not adopt (vendor-specific)

[Ewoc] `IndoorBikeParser.kt` is explicitly "Tunturi-specific", with a different flag map: bits 8–14 mean total energy, energy per hour, energy per minute, heart rate, MET, elapsed, remaining, so the SIG layout shifts by one bit from bit 9 on, and bit 0 has no field. Its Total Distance is divided by 10 ("Tunturi reports decimetres"). That contradicts the SIG's 1 m resolution. We do **not** use the Ewoc flag map or the distance division. The distance question goes on the bench list (§9).

## 3. Fitness Machine Feature (0x2ACC)

Read-only, 8 bytes: a 32-bit "Fitness Machine Features" word followed by a 32-bit "Target Setting Features" word, both little-endian. The SIG XML is a stub and does not give the bits. **Bit meanings are [x-checked: pycycling] only**, from `ftms_parsers/fitness_machine_feature.py`. No second decoder parses this characteristic.

### 3.1 Fitness Machine Features word (bytes 0–3)

| Bit | Name (pycycling) | Matters to us |
|---|---|---|
| 0 | Average Speed | |
| 1 | **Cadence** | yes |
| 2 | Total Distance | |
| 3 | Inclination | no (we use simulation parameters, §4) |
| 4 | Elevation Gain | |
| 5 | Pace | |
| 6 | Step Count | |
| 7 | Resistance Level | |
| 8 | Stride Count | |
| 9 | Expended Energy | |
| 10 | **Heart Rate Measurement** | not needed, the strap is the HR source |
| 11 | Metabolic Equivalent | |
| 12 | Elapsed Time | |
| 13 | Remaining Time | |
| 14 | **Power Measurement** | **yes** |
| 15 | Force on Belt and Power Output | |
| 16 | User Data Retention | |

### 3.2 Target Setting Features word (bytes 4–7)

| Bit | Name (pycycling) | Matters to us |
|---|---|---|
| 0 | Speed Target Setting | |
| 1 | Inclination Target Setting | no, we use simulation parameters |
| 2 | Resistance Target Setting | no |
| 3 | **Power Target Setting** | **yes (ERG, §4.5)** |
| 4 | Heart Rate Target Setting | no |
| 5–12 | Targeted expended energy, steps, strides, distance, training time, 2 / 3 / 5 HR zones | no |
| 13 | **Indoor Bike Simulation Parameters** | **yes (gears and grade, §4.3–§4.4)** |
| 14 | Wheel Circumference | not needed |
| 15 | Spin Down Control | not needed |
| 16 | Targeted Cadence | not needed |

**Rule for the app:** require Power Measurement (bit 14), Power Target Setting (target bit 3) and Indoor Bike Simulation Parameters (target bit 13) before running ERG or gears. If any is missing, show it and fall back to free ride. [derived from §4 of the spec; unverified against the FTMS text]

**Vector F1 and F2 (§8) verify these bits [x-checked: pycycling].**

## 4. Control Point (0x2AD9)

### 4.1 Procedure

1. Discover the service `0x1826` and its characteristics. Read the Fitness Machine Feature (§3) and check the bits we need.
2. Enable **indications** on the Control Point, by writing the CCCD with the Android constant `ENABLE_INDICATION_VALUE` (the numeric CCCD value is not quoted in the fetched sources, so it is **unverified** here). The Control Point is indicate-only in the SIG service table [SIG-svc: Indicate mandatory, Notify excluded]. [Ewoc `FtmsBleClient.kt`, `writeControlPointCccd`, cross-checked.]
3. Enable **notifications** on Indoor Bike Data (CCCD `0x0001`) and Fitness Machine Status (CCCD `0x0001`, if the service has it). [Ewoc writes Indoor Bike Data notify; SIG-svc for Status.]
4. Send **Request Control** (`0x00`) and **wait for its Response Code indication** with result `0x01` before any other opcode. [Ewoc: `requiresControlOwnership = opcode != REQUEST_CONTROL`, and `writeControlPoint` refuses every other opcode until granted; cross-checked.]
5. Then send commands. Writes use **write with response** (`WRITE_TYPE_DEFAULT`), because the Control Point does not allow write without response [SIG-svc]. [Ewoc `writeCharacteristic(..., WRITE_TYPE_DEFAULT)`, cross-checked.]
6. Every command gets a Response Code indication `0x80`, request opcode, result. Only one command may be in flight: the next command should wait for the response. [Ewoc `BUSY` state queues the latest target and drops older ones ("last wins"); this is Ewoc's policy, cross-checked as practice.]
7. Losing control: a Reset (`0x01`) with success result drops control [Ewoc `updateControlOwnershipFromResponse`]. The Fitness Machine Status `0xFF` (Control Permission Lost) is the other way to learn it (§5). [pycycling `FitnessMachineStatus.CONTROL_PERMISSION_LOST`, cross-checked as code, spec wording unverified.]

### 4.2 Opcodes and parameter layout

Values are little-endian. "Resolution" is the value of one count.

| Opcode | Command | Parameter | Units and resolution | Sources |
|---|---|---|---|---|
| `0x00` | Request Control | none | — | [pycycling] [Ewoc] |
| `0x01` | Reset | none | — | [pycycling] [Ewoc] |
| `0x04` | Set Target Resistance Level | uint8 | 0.1 (unitless) | [pycycling] [Ewoc uses level as a byte] |
| `0x05` | **Set Target Power** | **sint16** | watts, **1 W** | [pycycling] [Ewoc: `byteArrayOf(0x05, lo, hi)`] |
| `0x07` | **Start or Resume** | none | — | [pycycling] only |
| `0x08` | **Stop or Pause** | uint8: `0x01` = Stop, `0x02` = Pause | — | [pycycling] [Ewoc uses 0x01 stop and 0x02 pause] |
| `0x11` | **Set Indoor Bike Simulation Parameters** | see §4.3 | see below | [pycycling] only |
| `0x12` | Set Wheel Circumference | uint16 | 0.1 mm | [pycycling] only |
| `0x13` | Set Spin Down Control | uint8 | `0x01` start, `0x02` ignore | [pycycling] only |
| `0x14` | Set Targeted Cadence | uint8 in pycycling; the spec width is unverified | 1 rpm | [pycycling] only, **width disputed by the field name** |
| `0x80` | Response Code (indication, device to host) | see §4.4 | — | [pycycling] [Ewoc] |

Notes:
- Stop and Pause both take the parameter byte. A Stop without the parameter byte is not what we send. [pycycling and Ewoc agree on 0x01 and 0x02.]
- Start or Resume (`0x07`) is required before a session starts. The Ewoc code does not use it. **Unverified** whether the D500 needs `0x07` before it applies ERG targets, or accepts them directly after Request Control. This goes on the bench list (§9).

### 4.3 Set Indoor Bike Simulation Parameters (`0x11`)

Payload = `0x11` + 4 fields:

| Offset | Field | Type | Unit and resolution | Example for 3.5 % grade | Source |
|---|---|---|---|---|---|
| 1–2 | Wind Speed | **sint16** | m/s, 0.001 | 0 (`00 00`) | [pycycling] only |
| 3–4 | Grade (inclination) | **sint16** | %, **0.01** | 350 (`5E 01`) | [pycycling] only |
| 5 | Coefficient of Rolling Resistance (Crr) | **uint8** | unitless, 0.0001 | 40 (`28`) = 0.0040 | [pycycling] only |
| 6 | Wind Resistance Coefficient (Cw) | **uint8** | kg/m, 0.01 | 51 (`33`) = 0.51 kg/m | [pycycling] only |

- Full 3.5 % example: `11 00 00 5E 01 28 33` [verified by pycycling, vector CP8].
- Grade range: at 0.01 % resolution, the sint16 grade field covers -327.68 % to +327.67 %. The D500's documented maximum is **15 % simulated grade** (spec §2). The gear model in spec §4.4 must keep the total offset within ±15 %, which is raw **±1500**. The device's own range comes from the **Supported Inclination Range** characteristic (`0x2AD5`) [Nordic, SIG-svc]. Its format is **unverified** (not retrieved), so read it on the bench before trusting the 15 % number.
- Ewoc does not implement this opcode (its `FtmsController.kt` has only `0x00`, `0x01`, `0x04`, `0x05`, `0x08`). So the simulation encoding is **single-source** [pycycling]. Treat it as unverified until the bench test.

### 4.4 Response Code indication (`0x80`)

Layout: `0x80`, request opcode (the one being answered), result code.

| Result | Name | Meaning | Retry policy (our choice, not in spec) |
|---|---|---|---|
| `0x01` | Success | done | none |
| `0x02` | Op code not supported | the trainer lacks this feature | do not retry. Disable the feature (for example grade) and tell the user. |
| `0x03` | Invalid parameter | our value is out of range | do not resend the same value. Clamp to the device range and log. |
| `0x04` | Operation failed | the trainer could not do it | retry after a short back-off, a small fixed number of times |
| `0x05` | Control not permitted | we do not hold control | request control again once. If that fails, stop and show "trainer not under control". |

Result codes from [pycycling `FTMSControlPointResponseResultCode`], cross-checked against [Ewoc] (which only checks result `0x01` for Request Control and Reset). Parsing: pycycling reads `message[1]` as the request and `message[2]` as the result; Ewoc checks `size >= 3` and `value[0] == 0x80`. Both agree.

**Timing:** how long the trainer takes to reply is **unverified**. Our retry logic needs a timeout per command; start with a timeout and measure it on the bench (§9).

## 5. Fitness Machine Status (0x2ADA)

Notifications: 1 byte opcode, then parameters. **Opcode table and layouts are single-source [pycycling `ftms_parsers/fitness_machine_status.py`]**. Status opcodes are a separate numbering from the Control Point opcodes. The SIG service table says Status notifications are mandatory when the Control Point is supported.

| Opcode | Event | Parameters | What our app does |
|---|---|---|---|
| `0x01` | Reset | none | Control is lost. Re-request control, then re-send the target. |
| `0x02` | Stopped or paused by user | `0x01` stopped, `0x02` paused | Pause the ride engine and the ERG target. Ask the rider to resume. |
| `0x03` | Stopped by safety key | none | Stop the session and tell the rider. |
| `0x04` | Started or resumed by user | none | Resume. Re-send the current target if it was cleared. |
| `0x08` | Target power changed | sint16 W | Compare with the target we sent. A different value means the trainer rejected or altered it: log it. |
| `0x12` | Indoor bike simulation parameters changed | wind sint16 (0.001 m/s), grade sint16 (0.01 %), Crr uint8 (0.0001), Cw uint8 (0.01 kg/m) | Compare with the gear offset we sent. Log any difference. |
| `0x14` | Spin down status | uint8 | Not used. |
| `0x15` | Targeted cadence changed | uint16 rpm | Not used. |
| `0xFF` | **Control permission lost** | none | Set control to not granted. Re-request control. Stop sending targets until granted. |

Other opcodes (`0x05`–`0x07` speed, incline, resistance target changes; `0x09`–`0x11` heart-rate and other target changes; `0x13` wheel circumference; `0x0A`–`0x0E` targeted expended energy, steps, strides, distance and training time) are in pycycling's enum and are not needed.

**Disputed:** pycycling reads the simulation wind speed and grade as **unsigned** in the Status parser, but as **signed** in its Control Point encoder. Vector ST3 (§8) shows the disagreement: a -2.00 % grade decodes to 653.36 %. The FTMS text was not retrieved, so the signedness is **unverified**. The codec uses signed values, the same as the Control Point parameter.

## 6. Heart Rate Measurement (0x2A37)

[SIG-XML] for the layout, [pycycling `heart_rate_service.py`] as the second decoder.

### 6.1 Flags (8 bits)

| Bit | Name | Values |
|---|---|---|
| 0 | Heart Rate Value Format | 0 = uint8 bpm, 1 = uint16 bpm |
| 1–2 | Sensor Contact Status | `0` not supported, `1` not supported, `2` supported but **not detected**, `3` supported and **detected** |
| 3 | Energy Expended present | 1 = a uint16 energy field follows the HR value. Units **kJ** (the XML's unit label says joule; the XML's own text says kilo joules). |
| 4 | RR-Interval present | 1 = one or more RR intervals follow, to the end of the payload |
| 5–7 | Reserved | ignore |

### 6.2 Field order

1. Flags (1 byte).
2. Heart Rate: uint8 (bit 0 = 0) or uint16 LE (bit 0 = 1), bpm, resolution 1.
3. Energy Expended, uint16 LE, kJ, only if bit 3 is set.
4. RR-Interval values: uint16 LE each, unit **1/1024 s**, repeated **until the end of the payload**, only if bit 4 is set. The oldest is first.

### 6.3 Decoding rules

- **Sensor contact:** contact is detected only when the two bits equal `3`. Values `0` and `1` mean "not supported", value `2` means "supported, no contact". Do **not** treat any nonzero value as contact. [SIG-XML; pycycling gets this wrong, see §6.5]
- **RR conversion:** `ms = raw * 1000 / 1024`, rounded to the nearest integer ms (round half up). For example 900 raw → 878.9 → 879 ms; 1024 raw → 1000 ms. Keep the raw 1/1024 values in the recorder, and convert in the domain layer.
- **RR list:** RR intervals are optional. Bit 4 = 0 means **no RR data (null or absent)**, not an empty list. Bit 4 = 1 with **zero** following bytes is an edge case: return an **empty list** and do not fail. [unverified against spec text; pycycling returns `[]` for this case, cross-checked]
- **Odd trailing byte** inside the RR region: ignore the stray byte and log a warning. [pycycling ignores it; spec says the RR fields are uint16 so an odd byte is malformed]
- **Energy in kJ** is a different unit from the Indoor Bike Data energy in kcal (§2.2).
- The number of RR intervals per notification is not fixed: it is derived from the payload length (SIG-XML note). Do not assume two.

### 6.4 Coospo HR7 expectations

The HR7 is expected to send RR intervals, which the spec §2 calls "expected but unverified". Nothing in this session could verify the HR7's behaviour. **Unverified.**

### 6.5 Disagreement with pycycling

[pycycling] computes `sensor_contact = bool(flags & 0x06)`. Any nonzero contact value is then "contact detected", so values `1` and `2` (not supported, and supported but not detected) both read as contact. Vectors HR4 and HR5 (§8) show it. The SIG XML is clear that only `3` means detected. **We follow the XML.** pycycling agrees on the other 9 HR vectors.

## 7. Android BLE notes for the transport boundary

- **Permissions** [Android, bt-permissions page]: for scanning on Android 12 (API 31) and later, declare `BLUETOOTH_SCAN`. Declare it with `android:usesPermissionFlags="neverForLocation"` if the app does not derive physical location from scan results. Doing so filters some BLE beacons out of the scan results, which does not affect the trainer or the strap. Declare `BLUETOOTH_CONNECT` for connecting to and writing to paired devices. Ewoc checks `BLUETOOTH_CONNECT` before every GATT call. [Ewoc `hasBluetoothConnectPermission`, cross-checked as practice.]
- **Connect:** the reference page for `BluetoothDevice` (fetched 2026-11-18) says that `connectGatt(Context, boolean autoConnect, BluetoothGattCallback)` is **deprecated in API level 37**, and so is the variant with transport, PHY and handler. The replacement is not in the fetched text, so **unverified**. The `autoConnect` semantics are not stated in the fetched text. Plan for `autoConnect = false` for the first connect and a reconnect policy in the app layer (spec §4.1), and check the current API before writing the wrapper.
- **Enabling notifications and indications:** write the CCCD and call `setCharacteristicNotification(characteristic, true)` [Android, BluetoothGatt reference]. Control Point uses **indication** (`0x0002`), Indoor Bike Data and Heart Rate Measurement use **notification** (`0x0001`) [SIG-svc for the properties, Ewoc for the code path].
- **One GATT operation at a time:** the CCCD writes must go one after another. Ewoc does this with a setup state machine (`SetupStep.CP_CCCD`, then `SetupStep.BIKE_CCCD`) [Ewoc, cross-checked as practice]. **The Android documentation text for this rule was not found in the fetched pages**, so the rule is **unverified** from Google's text. The codec and transport must treat it as a requirement.
- **Write with response** for the Control Point (see §4.1 step 5). [SIG-svc]
- **MTU:** `BluetoothGatt.requestMtu(int)` exists [Android reference]. The default ATT MTU and whether Android fragments or truncates long notifications are **unverified** in the fetched text. Derived, not measured: the maximal Indoor Bike Data vector (§8 IBD10) is 30 bytes. With the default 23-byte ATT MTU the value field is 20 bytes, so a notification that long needs an MTU above 23. Request an MTU of at least 64 at connect and confirm the negotiated value on the bench.
- **GATT status 133** (`GATT_ERROR`): a generic failure, typically a stale or failed connection. **Unverified** from the fetched pages. The recovery we plan is disconnect, close, and reconnect with a delay, and never reuse a `BluetoothGatt` object after a 133.
- **Thread hand-off:** Ewoc keeps only the latest Indoor Bike Data sample and posts it to the main thread at a fixed interval (200 ms, `INDOOR_BIKE_MAIN_THREAD_UPDATE_INTERVAL_MS`), so the UI never falls behind the notifications [Ewoc `FtmsBleClient.kt`, cross-checked as practice]. The overlay (spec §4.6, §7.3) needs the same: decode on the BLE side, and let the UI read at most one sample per 200 ms. Which thread Android delivers callbacks on is **unverified** in the fetched pages.

### 7.1 Raw session recorder (for replay, spec §7.5)

Record one line per event, in order:

| Field | Example | Notes |
|---|---|---|
| `t_ns` | monotonic nanoseconds (`elapsedRealtimeNanos`) | ordering and timing |
| `dir` | `N` (notification), `I` (indication), `W` (write), `R` (read), `C` (CCCD write), `S` (connection state) | |
| `char` | `2AD2` or the full UUID | |
| `status` | GATT status (0 for success), or `-` | |
| `hex` | `4400C409B400FA00` | the exact bytes, unchanged |

Do **not** write device MAC addresses, serial numbers or the HR strap's identity into a file that goes in the repo. The fixtures in the repo are synthetic (§8). Real recordings stay out of git: the repository is public and the rules in `CLAUDE.md` forbid committing raw recordings of the owner's body.

## 8. Test vectors

Each vector: hex, then expected values. The expected values were computed by hand from §2–§6, then checked by running the hand-written table against [pycycling 0.4.1] in a scratch venv (`python3 -I`, MIT). **Result: 41 of 44 agree.** The three disagreements are real pycycling defects and are recorded as such (§6.5 and §5). The verifier is in the scratchpad, not committed.

### 8.1 Indoor Bike Data (10 vectors, all agree with pycycling)

| ID | Hex (LE) | Expected | Note |
|---|---|---|---|
| IBD1 | `41 00 C8 00` | flags `0x0041` (bit 0 = More Data, so speed absent; bit 6 power). Power **200 W** | speed absent |
| IBD2 | `44 00 C4 09 B4 00 FA 00` | flags `0x0044`. Speed **25.00 km/h** (`0x09C4` = 2500), cadence **90.0 rpm** (`0x00B4` = 180, /2), power **250 W** | the common case |
| IBD3 | `06 00 B8 0B 22 0B A0 00` | flags `0x0006`. Speed **30.00 km/h**, average speed **28.50 km/h** (`0x0B22`), cadence **80.0 rpm** | bit 1 = average speed (disputed, §2.4) |
| IBD4 | `10 00 E8 03 39 30 00` | flags `0x0010`. Speed **10.00 km/h**, distance **12345 m** (`0x003039` as uint24) | distance is uint24 |
| IBD5 | `00 02 00 00 96` | flags `0x0200`. Speed **0.00 km/h**, heart rate **150 bpm** | HR in IBD |
| IBD6 | `41 00 EC FF` | flags `0x0041`. Power **-20 W** (`0xFFEC`, sint16) | signed power |
| IBD7 | `00 01 00 00 2C 01 E8 03 10` | flags `0x0100`. Speed **0.00**, total energy **300 kcal**, energy per hour **1000 kcal/h**, energy per minute **16 kcal/min** | expended-energy triple |
| IBD8 | `00 18 00 00 F2 02 B0 04` | flags `0x1800`. Speed **0.00**, elapsed **754 s**, remaining **1200 s** | |
| IBD9 | `A0 04 00 00 19 00 B4 00 2D` | flags `0x04A0` (bits 5, 7, 10). Speed **0.00**, resistance **25**, average power **180 W**, MET **4.5** (`0x2D` = 45, /10) | MET is /10 |
| IBD10 | `FE 1F 10 0E 7A 0D B4 00 AB 00 D2 04 00 1E 00 2C 01 18 01 F4 01 08 07 1E A0 50 08 07 58 02` | flags `0x1FFE` (all of bits 1–12, bit 0 clear). Speed **36.00**, average speed **34.50**, cadence **90.0**, average cadence **85.5**, distance **1234 m**, resistance **30**, power **300 W**, average power **280 W**, energy **500 kcal**, **1800 kcal/h**, **30 kcal/min**, HR **160**, MET **8.0**, elapsed **1800 s**, remaining **600 s** | maximal, 30 bytes |

### 8.2 Heart Rate Measurement (11 vectors)

| ID | Hex | Expected | pycycling |
|---|---|---|---|
| HR1 | `00 48` | uint8, flags 0 (contact not supported). bpm **72**, no RR, no energy | agrees |
| HR2 | `01 C8 00` | uint16 format. bpm **200** | agrees |
| HR3 | `06 96` | contact **detected** (bits = 3). bpm **150** | agrees |
| HR4 | `04 3C` | contact **supported, not detected** (bits = 2). bpm **60** | **DISAGREES**: reads contact as detected (§6.5) |
| HR5 | `02 46` | contact status 1 = **not supported**. bpm **70** | **DISAGREES**: same defect (§6.5) |
| HR6 | `16 48 00 04 00 02` | flags `0x16` (contact detected, RR). bpm **72**, RR **[1024, 512]** raw = **[1000.0, 500.0] ms** | agrees |
| HR7 | `17 96 00 84 03 00 04` | uint16 format, contact detected, RR. bpm **150**, RR **[900, 1024]** raw = **[879, 1000] ms** (900 → 878.9, rounded) | agrees |
| HR8 | `18 78 64 00 00 04` | energy present (bit 3), RR present. bpm **120**, energy **100 kJ**, RR **[1024]** = **[1000.0] ms**. Note the energy comes before RR | agrees |
| HR9 | `08 5A 58 02` | energy only. bpm **90**, energy **600 kJ**, no RR | agrees |
| HR10 | `10 50` | RR flag set, **no RR bytes**. bpm **80**, RR **empty list** | agrees |
| HR11 | `10 50 00 04 01` | RR flag set, one RR (1024) and a stray byte. bpm **80**, RR **[1000.0] ms**, stray byte ignored | agrees |

### 8.3 Control Point commands to encode (9 vectors, all agree with pycycling)

| ID | Command | Expected bytes |
|---|---|---|
| CP1 | Request Control | `00` |
| CP2 | Reset | `01` |
| CP3 | Set Target Power 200 W | `05 C8 00` |
| CP4 | Set Target Power 1500 W | `05 DC 05` |
| CP5 | Start or Resume | `07` |
| CP6 | Stop | `08 01` |
| CP7 | Pause | `08 02` |
| CP8 | Simulation: wind 0 m/s, **grade +3.50 %** (raw 350), Crr 0.0040 (raw 40), Cw 0.51 kg/m (raw 51) | `11 00 00 5E 01 28 33` |
| CP9 | Simulation: wind 0, **grade -2.00 %** (raw -200), Crr 0.0040, Cw 0.51 | `11 00 00 38 FF 28 33` |

CP3 and CP4 are also written by Ewoc (`05`, lo, hi), so they are cross-checked twice. CP8 and CP9 are single-source (pycycling).

### 8.4 Response Code indications (5 vectors, all agree)

| ID | Bytes | Meaning |
|---|---|---|
| RC1 | `80 00 01` | Request Control succeeded. Control granted |
| RC2 | `80 05 02` | Set Target Power: op code not supported |
| RC3 | `80 11 05` | Simulation parameters: control not permitted |
| RC4 | `80 05 03` | Set Target Power: invalid parameter |
| RC5 | `80 08 04` | Stop: operation failed |

### 8.5 Fitness Machine Status (7 vectors: 6 agree, 1 disagrees)

| ID | Bytes | Expected | pycycling |
|---|---|---|---|
| ST1 | `08 C8 00` | target power changed to **200 W** | agrees |
| ST2 | `12 00 00 5E 01 28 33` | simulation changed, grade **+3.50 %** | agrees |
| ST3 | `12 00 00 38 FF 28 33` | simulation changed, grade **-2.00 %** (signed) | **DISAGREES**: unsigned, reads 653.36 % (§5) |
| ST4 | `FF` | **control permission lost** | agrees |
| ST5 | `02 01` | stopped by user | agrees |
| ST6 | `02 02` | paused by user | agrees |
| ST7 | `04` | started or resumed by user | agrees |

### 8.6 Fitness Machine Feature (2 vectors, both agree)

| ID | Bytes | Expected |
|---|---|---|
| FMF1 | `06 54 00 00` | Features word `0x00005406`: cadence (bit 1), total distance (bit 2), HR (bit 10), elapsed time (bit 12), power measurement (bit 14) are supported |
| FMF2 | `08 20 00 00` | Target word `0x00002008`: power target setting (bit 3) and indoor bike simulation parameters (bit 13) are supported |

Summary: 44 vectors, 41 agree with pycycling, 3 disagree. HR4 and HR5 are a pycycling defect against the SIG XML. ST3 depends on the signedness of the simulation grade in the Status characteristic, which is **unverified** because the FTMS text was not retrieved; pycycling's own Control Point encoder treats the same field as signed.

## 9. Open questions for the bench test (D500 and HR7)

1. **Flag bits 1 and 2 (§2.4):** send a session with cadence on. Does the D500 set bit 1 or bit 2 for instantaneous cadence? Check against the cadence the trainer displays. This decides the codec's field order for every notification.
2. **Total Distance units (§2.5):** is the uint24 in metres (SIG) or decimetres (Ewoc's Tunturi reading)? Compare with the trainer's display after a known distance.
3. **More Data (§2.3):** does the D500 ever send bit 0 = 1 (speed absent) or a record split across two notifications?
4. **Fitness Machine Status:** does the D500 send `0x08` after a target power write, `0x12` after a simulation write, and `0xFF` on control loss? Record which ones arrive.
5. **Simulation resolution (§4.3):** which grade resolution does it honour? Send `0.01 %` steps and read the displayed grade. Also read the Supported Inclination Range (`0x2AD5`) to get the real limits. Is the 15 % maximum (spec §2) in that characteristic?
6. **Response timing (§4.4):** how long after a write does the `0x80` indication arrive? Measure the median and the 95th percentile before setting the timeout.
7. **Start or Resume (§4.2):** does the D500 apply a `0x05` target before a `0x07`? Does it need `0x07` at all?
8. **Simulation signedness (§5):** send a negative grade and read the Status echo. Is the field signed?
9. **Control Point indications with notifications on the same connection (§4.1):** confirm that indications are delivered when Indoor Bike Data notifications are active at the same time.
10. **HR7 RR intervals (§6.4):** is bit 4 set? How many RR values per notification, and are they in 1/1024 s? Compare the RR average with the strap's own report.
11. **HR7 contact bits:** does it report status `2` when the strap is off the chest, and `3` when on?
12. **MTU (§7):** what MTU does the D500 negotiate, and does a 30-byte maximal Indoor Bike Data notification arrive whole?
13. **Battery Service (`0x180F`, §1):** the UUID is unverified. Check it on the bench.

## 10. Items to fix in the spec (`docs/spec.md`) and the codecs

- §4.1 says "Read Fitness Machine Feature and Status". Add the bit checks in §3 as a precondition for ERG and gears.
- §2 gives the D500's 15 % maximum grade. Keep it, and add the device-read limit (`0x2AD5`) as the source of truth once measured.
- §4.4's gear offset must stay within ±15 % (±1500 raw) by construction.
- The RR interval rounding rule (§6.3) should live in the domain layer, not the codec.

**Next step for the implementer:** write the codecs from §2–§6 and the vectors in §8 as the test oracle. Start with the two disputed points (§2.4 and §6.5) as explicit test cases, so a later bench result can flip them with one change.
