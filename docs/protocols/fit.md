# FIT activity file: protocol notes for our writer

Status: research note for the FIT writer (spec §4.8, §8.2). **The writer is implemented** (stage M2b-1, ADR 0007,
`app.overdrive.core.fit`): `FitWriter` reproduces the worked example of section 6 byte for byte (with one
documented difference in `local_timestamp`, ADR 0007) and every open question of section 8 is a field of
`FitOptions`, still open until a real import into intervals.icu.
Read on 2026-11-18. Only facts from Garmin's published SDK files are used here; the SDK code is not copied.

## 0. Sources and versions

| Source | Version | What was used |
|---|---|---|
| npm `@garmin/fitsdk` (`https://registry.npmjs.org/@garmin/fitsdk/-/fitsdk-21.222.0.tgz`) | 21.222.0, profile 21.222.0 "Release" | `src/profile.js` (generated from Garmin's Profile; message, field and type tables), `src/fit.js` (base types, invalid values), `src/crc-calculator.js`, `src/encoder.js`, `src/decoder.js`, `src/utils.js` (epoch), `LICENSE.txt` |
| Maven Central `com.garmin:fit` | 21.222.0 (latest, metadata updated 2026-11-17) | the jar, used as an independent decoder/encoder oracle (section 6) |

Not obtained: `Profile.xlsx` and the FIT Protocol PDF. The download page's SDK zip URLs I tried returned 404, and
`developer.garmin.com/fit/protocol/` is rendered by script, so its text was not readable. Numbers below come from
`profile.js`, which its header says is auto-generated from the same profile. Items that could only come from the
protocol PDF are marked "unverified".

A throwaway parser and writer were used (kept in the scratchpad, not committed). The Java SDK was run with
`javac`/`java` in a scratch directory.

## 1. File structure

### 1.1 File header

The writer produces a 14-byte header (the 12-byte form has no CRC and the SDK accepts both sizes; we write 14).

| Offset | Size | Field | Value we write | Notes |
|---|---|---|---|---|
| 0 | 1 | header size | `0x0E` (14) | |
| 1 | 1 | protocol version | `0x20` (protocol 2.0) | The Java SDK writes `0x20` (its `Fit.ProtocolVersion.V2_0`). The JS SDK writes the literal `0x02`. Which one readers expect is unverified (open question 8.1). |
| 2 | 2, LE | profile version | `major * 1000 + minor` = `21222` (`0x52E6`) | Formula from the JS SDK encoder. The value is ours to choose; it only records which profile we followed. |
| 4 | 4, LE | data size | byte count of the records section | Excludes the header and the 2-byte file CRC. |
| 8 | 4 | data type | `.FIT` = `2E 46 49 54` | |
| 12 | 2, LE | header CRC | CRC-16 (section 1.4) over bytes 0 to 11 | Present in 14-byte headers. |

### 1.2 Record header byte

| Bit | Meaning |
|---|---|
| 7 | `1` = compressed-timestamp header; `0` = normal header. We write only normal headers. |
| 6 | `1` = definition message; `0` = data message. |
| 5 | `1` = developer field definitions follow the field definitions (definition messages only). We do not use developer fields. |
| 4 | not checked in the sources; we write `0` |
| 3 to 0 | local message type, 0 to 15 |

The JS SDK decoder (`decoder.js`) throws "unexpected compressed timestamp header" on bit 7, so the SDK does not
read compressed headers. We never write them. Their exact bit layout is not in the sources I read (unverified).

### 1.3 Definition and data messages

Definition message, in order:

| Bytes | Content |
|---|---|
| 1 | record header: `0x40 | local type` (`0x60` if developer fields follow) |
| 1 | reserved, `0x00` |
| 1 | architecture: `0x00` = little endian, `0x01` = big endian. We write `0x00`. |
| 2 | global message number, in the architecture's byte order |
| 1 | number of fields N |
| 3 each, N times | field definition: field number (1 byte), size in bytes (1 byte), base type byte (1 byte) |

The base type byte is the base type number (table in section 2) with bit 7 (`0x80`) set when the type is multi-byte
(the SDK's `baseTypeEndianFlag`). Field size must equal the base type size times the array length.

Data message: one header byte (the local type, bit 6 clear), then the field values in the order of the definition,
each in the definition's byte order. A field with no value is written as its base type's invalid value (the JS
encoder does this in `#transformValue` when a value is null or undefined).

### 1.4 CRC-16 (file and header)

This is the CRC-16/ARC variant: reflected polynomial `0xA001` (normal `0x8005`), initial value `0x0000`, no final
XOR, bits and bytes processed least significant first. The SDK computes it with a nibble table, so this is the
form we should write. Each byte is processed as two 4-bit steps, low nibble first. For each step:

1. `t = table[crc & 0xF]`
2. `crc = (crc >> 4) & 0x0FFF`
3. `crc = crc ^ t ^ table[nibble]`

where `nibble` is the low (first) or high (second) 4 bits of the byte. The table is the SDK's `crcTable`, copied as
numbers:

| Index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| value | `0x0000` | `0xCC01` | `0xD801` | `0x1400` | `0xF001` | `0x3C00` | `0x2800` | `0xE401` |

| Index | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 |
|---|---|---|---|---|---|---|---|---|
| value | `0xA001` | `0x6C00` | `0x7800` | `0xB401` | `0x5000` | `0x9C01` | `0x8801` | `0x4400` |

Checks, all done in the scratchpad:

- The nibble form equals the bitwise CRC-16/ARC on 2,000 random buffers (0 to 300 bytes) and on the example file.
- Check value of `"123456789"` is `0xBB3D`, the standard CRC-16/ARC check value.
- The file CRC is stored little endian after the last record. Running the CRC over the whole file, including its
  own two bytes, gives `0x0000`.

The header CRC uses the same function over the first 12 bytes and is stored at offset 12, little endian.

## 2. Base types

Numbers from `fitBaseType` in the profile and the SDK's `fit.js` table. "Type byte" is the value written in a
field definition (with the endian flag). "Invalid" is the value readers treat as "no data".

| Name | Number (low 5 bits) | Type byte | Size | Invalid value |
|---|---|---|---|---|
| enum | 0 | `0x00` | 1 | `0xFF` |
| sint8 | 1 | `0x01` | 1 | `0x7F` |
| uint8 | 2 | `0x02` | 1 | `0xFF` |
| sint16 | 3 | `0x83` | 2 | `0x7FFF` |
| uint16 | 4 | `0x84` | 2 | `0xFFFF` |
| sint32 | 5 | `0x85` | 4 | `0x7FFFFFFF` |
| uint32 | 6 | `0x86` | 4 | `0xFFFFFFFF` |
| string | 7 | `0x07` | 1 (per char, NUL-terminated) | `0x00` |
| float32 | 8 | `0x88` | 4 | `0xFFFFFFFF` |
| float64 | 9 | `0x89` | 8 | `0xFFFFFFFFFFFFFFFF` |
| uint8z | 10 | `0x0A` | 1 | `0x00` |
| byte | 13 | `0x0D` | 1 | `0xFF` |
| uint16z | 11 | `0x8B` | 2 | `0x0000` |
| uint32z | 12 | `0x8C` | 4 | `0x00000000` |
| sint64 | 14 | `0x8E` | 8 | `0x7FFFFFFFFFFFFFFF` |
| uint64 | 15 | `0x8F` | 8 | `0xFFFFFFFFFFFFFFFF` |
| uint64z | 16 | `0x90` | 8 | `0x0000000000000000` |

Notes:

- `uint16z`, `uint32z` and `uint8z` treat `0` as invalid. Our `serial_number` (`uint32z`) must therefore be non-zero.
- `byte` is an array of raw bytes; we do not need it except through the compressed speed/distance field, which we do not write.
- The `fitBaseType` table in the profile gives the same decimal numbers (for example `131` = `0x83` sint16).

## 3. Time

- FIT time is seconds since **1989-12-31T00:00:00Z** (SDK `utils.js`: `FIT_EPOCH_MS = 631065600000`).
- Unix to FIT: `fit = unix - 631065600`. FIT to Unix: `unix = fit + 631065600`.
- Type `date_time` and `local_date_time` are `uint32`. Their profile minimum is `0x10000000` (268435456). Smaller
  values are not dates (the profile does not give their meaning in the sources I read). Our timestamps are always
  above that, which is 1998-07-01 onward.
- `local_date_time` (for example `activity.local_timestamp`) holds local wall-clock time as FIT seconds, i.e.
  `unix + UTC offset - 631065600`.
- Example: 2026-11-18T10:00:00Z is Unix `1794996000`, FIT `1163930400` (`0x45602B20`).

Timestamps are in whole seconds. Sub-second precision is not needed at 1 Hz. The `timestamp_16` and `time128`
fields exist in the profile but are not used here.

## 4. Messages we write

Global message numbers are from the profile's `MesgNum` table. Field numbers, base types, scale and units are from
`profile.js`. "Invalid" is the base type's invalid value (section 2). A value written as invalid is a gap.
`field 253` is the common `timestamp` field, and `254` is `message_index`.

### 4.1 `file_id` (global 0)

Profile comment: "Must be first message in file."

| Field | Name | Type | Scale | Units | Notes |
|---|---|---|---|---|---|
| 0 | type | enum (file) | | | `4` = activity |
| 1 | manufacturer | uint16 (manufacturer enum) | | | `255` = development (enum `development`). See 8.3. |
| 2 | product | uint16 | | | Our own value (we use `1`). |
| 3 | serial_number | uint32z | | | Non-zero. |
| 4 | time_created | uint32 (date_time) | | s | File creation time. |
| 5 | number | uint16 | | | Optional. |

### 4.2 `event` (global 21)

Used for timer start and stop. Timestamp field `253` is required in practice.

| Field | Name | Type | Scale | Notes |
|---|---|---|---|---|
| 253 | timestamp | uint32 (date_time) | | s |
| 0 | event | enum (event) | | `0` = timer |
| 1 | event_type | enum (eventType) | | `0` = start, `1` = stop, `4` = stop_all |
| 3 | data | uint32 | | Not used by us. |

### 4.3 `record` (global 20), one per second

| Field | Name | Type | Scale | Units | Invalid |
|---|---|---|---|---|---|
| 253 | timestamp | uint32 (date_time) | 1 | s | |
| 3 | heart_rate | uint8 | 1 | bpm | `0xFF` |
| 4 | cadence | uint8 | 1 | rpm | `0xFF` |
| 5 | distance | uint32 | 100 | m | `0xFFFFFFFF` |
| 6 | speed | uint16 | 1000 | m/s | `0xFFFF` |
| 7 | power | uint16 | 1 | W | `0xFFFF` |
| 73 | enhanced_speed | uint32 | 1000 | m/s | `0xFFFFFFFF` |

- Profile: `speed` (6) has a component field, `enhanced_speed` (73). `enhanced_speed` has a 32-bit range (no
  65.5 m/s limit). Our recommendation is to write both fields with the same value. Reason: `speed` is the field
  older readers take. This is a recommendation, not a profile requirement. intervals.icu and Strava behaviour is
  open (8.2).
- Position fields (`position_lat` 0 and `position_long` 1, sint32 semicircles, invalid `0x7FFFFFFF`) are only
  needed if we want a map. Indoor virtual rides do not need them. Not written.
- `distance` is the cumulative distance in metres.
- Gaps: a missing heart rate or power is written as its invalid value, so every record has the same field set
  (one definition per record layout).

### 4.4 `lap` (global 19), one per workout step

| Field | Name | Type | Scale | Units | Notes |
|---|---|---|---|---|---|
| 253 | timestamp | uint32 (date_time) | 1 | s | Lap end. |
| 2 | start_time | uint32 (date_time) | 1 | s | |
| 7 | total_elapsed_time | uint32 | 1000 | s | |
| 8 | total_timer_time | uint32 | 1000 | s | |
| 9 | total_distance | uint32 | 100 | m | |
| 13 | avg_speed | uint16 | 1000 | m/s | Component `enhanced_avg_speed` (110). |
| 14 | max_speed | uint16 | 1000 | m/s | Component `enhanced_max_speed` (111). |
| 15 | avg_heart_rate | uint8 | 1 | bpm | |
| 16 | max_heart_rate | uint8 | 1 | bpm | |
| 17 | avg_cadence | uint8 | 1 | rpm | |
| 18 | max_cadence | uint8 | 1 | rpm | |
| 19 | avg_power | uint16 | 1 | W | |
| 20 | max_power | uint16 | 1 | W | |
| 24 | lap_trigger | enum (lapTrigger) | | | `0` manual, `1` time, `2` distance, `7` session_end |
| 25 | sport | enum (sport) | | | `2` = cycling |
| 39 | sub_sport | enum (subSport) | | | `58` = virtual_activity |
| 254 | message_index | uint16 | | | Index of the lap, from 0. |

Enum names in the profile: `lapTrigger` 0 is `manual`, 7 is `sessionEnd`. Which trigger to use for a step lap is a
choice, not fixed by the profile (we would use `manual`, which is conventional; unverified).

### 4.5 `session` (global 18), one per file

| Field | Name | Type | Scale | Units | Notes |
|---|---|---|---|---|---|
| 253 | timestamp | uint32 (date_time) | 1 | s | |
| 2 | start_time | uint32 (date_time) | 1 | s | |
| 5 | sport | enum (sport) | | | `2` = cycling |
| 6 | sub_sport | enum (subSport) | | | `58` = virtual_activity |
| 7 | total_elapsed_time | uint32 | 1000 | s | |
| 8 | total_timer_time | uint32 | 1000 | s | |
| 9 | total_distance | uint32 | 100 | m | |
| 14 | avg_speed | uint16 | 1000 | m/s | Component `enhanced_avg_speed` (124). |
| 15 | max_speed | uint16 | 1000 | m/s | Component `enhanced_max_speed` (125). |
| 16 | avg_heart_rate | uint8 | 1 | bpm | |
| 17 | max_heart_rate | uint8 | 1 | bpm | |
| 18 | avg_cadence | uint8 | 1 | rpm | |
| 19 | max_cadence | uint8 | 1 | rpm | |
| 20 | avg_power | uint16 | 1 | W | |
| 21 | max_power | uint16 | 1 | W | |
| 22 | total_ascent | uint16 | 1 | m | Not needed. |
| 25 | first_lap_index | uint16 | 1 | | |
| 26 | num_laps | uint16 | 1 | | |
| 28 | trigger | enum (sessionTrigger) | | | `0` activity_end, `1` manual |
| 34 | normalized_power | uint16 | 1 | W | The profile defines the field only; the ride engine computes the value. |
| 35 | training_stress_score | uint16 | 10 | TSS | |
| 36 | intensity_factor | uint16 | 1000 | IF | |
| 48 | total_work | uint32 | 1 | J | |

The fields `normalized_power` (34), `training_stress_score` (35), `intensity_factor` (36) and `total_work` (48, joules)
are optional in the format. The spec's summary (§8.2) asks for normalised power and load, so the writer should set
them when the ride engine computes them. `total_moving_time` (59) also exists and is not used.

### 4.6 `activity` (global 34), one per file, last

| Field | Name | Type | Scale | Units | Notes |
|---|---|---|---|---|---|
| 253 | timestamp | uint32 (date_time) | 1 | s | |
| 0 | total_timer_time | uint32 | 1000 | s | |
| 1 | num_sessions | uint16 | 1 | | `1` |
| 2 | type | enum (activity) | | | `0` = manual, `1` = auto_multi_sport |
| 3 | event | enum (event) | | | `26` = activity |
| 4 | event_type | enum (eventType) | | | `1` = stop |
| 5 | local_timestamp | uint32 (local_date_time) | 1 | s | Section 3. |

### 4.7 `hrv` (global 78), RR intervals

| Field | Name | Type | Scale | Units | Notes |
|---|---|---|---|---|---|
| 0 | time | uint16, array | 1000 | s | Each RR interval in seconds × 1000 (800 ms is `800`). Invalid `0xFFFF`. |

- The hrv message in the profile has no timestamp field. It holds only the `time` array. Where in the stream it
  goes relative to records, and how a reader times it, are not stated in the profile (conventional, unverified).
  It seems reasonable to write each `hrv` just after the record for the second it covers (open question 8.4).
- Array length is set by the definition's size: the definition size is `N × 2` bytes for N intervals. Each HRV
  message uses its own local type and definition, so the sizes differ per message.

### 4.8 Optional: `device_info` (global 23)

Used once, with `device_index` 0 (`creator`), to name the device. Fields: `device_index` (0, uint8), `device_type`
(1, uint8), `manufacturer` (2), `serial_number` (3, uint32z), `product` (4, uint16), `software_version` (5, uint16,
scale 100), `source_type` (25, enum). Not needed for import; optional.

### 4.9 Enum values we use

| Enum (profile name) | Value | Name in profile | Used in |
|---|---|---|---|
| file (`file`) | 4 | activity | file_id.type |
| manufacturer (`manufacturer`) | 255 | development | file_id.manufacturer |
| sport (`sport`) | 2 | cycling | lap.sport, session.sport |
| subSport (`subSport`) | 58 | virtualActivity | lap.sub_sport, session.sub_sport |
| subSport (for comparison) | 6 | indoorCycling | not used; see 8.2 |
| event (`event`) | 0 | timer | event.event |
| event (`event`) | 26 | activity | activity.event |
| eventType (`eventType`) | 0, 1, 4 | start, stop, stopAll | event.event_type, activity.event_type |
| lapTrigger (`lapTrigger`) | 0, 7 | manual, sessionEnd | lap.lap_trigger |
| sessionTrigger (`sessionTrigger`) | 0, 1 | activityEnd, manual | session.trigger |
| activity (`activity`) | 0, 1 | manual, autoMultiSport | activity.type |

The sub-sport number `58` is from the profile's `subSport` table (`'58': 'virtualActivity'`). The spec calls it
"virtual activity".

## 5. Message order

Format requirements (from the file structure, section 1):

1. The file header comes first, then records, then the 2-byte CRC.
2. Each local message type must have a definition message before its first data message. A definition may be
   repeated later (it replaces the earlier one for that local type).
3. The first message in the file is `file_id` (profile comment on message 0, "Must be first message in file.").

Conventional (what Garmin devices and the SDK examples do; not required by the format):

4. `file_id`, then `event` (timer start), then the stream of `record` messages (with `hrv` messages where RR data
   exists), with `lap` and `event` (lap) at lap boundaries, then `event` (timer stop_all), `lap` (last lap), `session`,
   `activity`.
5. `session` after its laps; `activity` last, with `num_sessions` = 1.

Which of 4 and 5 intervals.icu and Strava actually need is unknown (open question 8.2).

## 6. Worked example

File: 376 bytes, generated by a throwaway script (`fit-tools/make_example.py` in the scratchpad, not committed).
It contains `file_id`, one timer `event` at start, three `record`s (1 s apart), a timer `event` stop_all, one `lap`,
one `session` and one `activity`. No HRV. Start 2026-11-18T10:00:00Z (FIT `1163930400`).

Values: record 0 is 120 bpm, 90 rpm, 200 W, 8.0 m/s, 0 m. Record 1 is 121 bpm, 91 rpm, 210 W, 8.1 m/s, 8.00 m.
Record 2 is 122 bpm, 92 rpm, 220 W, 8.2 m/s, 16.10 m. The lap and session totals are 3 s and 16.10 m.

### 6.1 Byte listing

Each line is one record. Bytes are shown as they are in the file (little endian within fields).

```
HEADER 0e 20 e6 52 68 01 00 00 2e 46 49 54 8a e1
```
- `0e`: header size 14. `20`: protocol 2.0. `e6 52`: profile 21222. `68 01 00 00`: data size 360.
  `2e 46 49 54`: ".FIT". `8a e1`: header CRC `0xE18A`.

```
DEF  local=0 gmn=0 fields=5 40 00 00 00 00 05 00 01 00 01 02 84 02 02 84 03 04 8c 04 04 86
```
- `40`: definition, local type 0. `00`: reserved. `00`: little endian. `00 00`: global 0 (`file_id`). `05`: 5 fields.
- `00 01 00`: field 0 (type), 1 byte, enum. `01 02 84`: field 1 (manufacturer), 2 bytes, uint16.
- `02 02 84`: field 2 (product), 2 bytes, uint16. `03 04 8c`: field 3 (serial), 4 bytes, uint32z.
- `04 04 86`: field 4 (time_created), 4 bytes, uint32.

```
DATA local=0 gmn=0 00 04 ff 00 01 00 01 00 00 00 20 2b 60 45
```
- `00`: data, local type 0. `04`: type = activity. `ff 00`: manufacturer 255 (development). `01 00`: product 1.
- `01 00 00 00`: serial 1. `20 2b 60 45`: time_created `0x45602B20` = 1163930400.

```
DEF  local=1 gmn=21 fields=3 41 00 00 15 00 03 fd 04 86 00 01 00 01 01 00
```
- `41`: definition, local 1. Global `15 00` = 21 (`event`). Fields: `fd 04 86` (253 timestamp, uint32),
  `00 01 00` (event, enum), `01 01 00` (event_type, enum).

```
DATA local=1 gmn=21 01 20 2b 60 45 00 00
```
- `01`: local 1. Timestamp `20 2b 60 45`. `00`: event = timer. `00`: event_type = start.

```
DEF  local=2 gmn=20 fields=7 42 00 00 14 00 07 fd 04 86 03 01 02 04 01 02 05 04 86 06 02 84 07 02 84 49 04 86
```
- `42`: definition, local 2. Global `14 00` = 20 (`record`). Seven fields: timestamp (253), heart_rate (3, uint8),
  cadence (4, uint8), distance (5, uint32), speed (6, uint16), power (7, uint16), enhanced_speed (0x49 = 73, uint32).

```
DATA local=2 gmn=20 02 20 2b 60 45 78 5a 00 00 00 00 40 1f c8 00 40 1f 00 00
DATA local=2 gmn=20 02 21 2b 60 45 79 5b 20 03 00 00 a4 1f d2 00 a4 1f 00 00
DATA local=2 gmn=20 02 22 2b 60 45 7a 5c 4a 06 00 00 08 20 dc 00 08 20 00 00
```
- Each: `02` local 2, then timestamp (LE), heart rate, cadence, distance, speed, power, enhanced speed.
- Record 0: `78` = 120 bpm, `5a` = 90 rpm, distance `00 00 00 00` = 0 (raw, scale 100), speed `40 1f` = 8000 (8.000 m/s),
  power `c8 00` = 200 W, enhanced speed `40 1f 00 00` = 8000.
- Record 1: `79` = 121, `5b` = 91, distance `20 03 00 00` = 800 (8.00 m), speed `a4 1f` = 8100, power `d2 00` = 210,
  enhanced `a4 1f 00 00`.
- Record 2: `7a` = 122, `5c` = 92, distance `4a 06 00 00` = 1610 (16.10 m), speed `08 20` = 8200, power `dc 00` = 220,
  enhanced `08 20 00 00`.

```
DATA local=1 gmn=21 01 22 2b 60 45 00 04
```
- Local 1 (`event`). Timestamp `0x45602B22` = t0+2. `00`: timer. `04`: stop_all.

```
DEF  local=3 gmn=19 fields=14 43 00 00 13 00 0e fd 04 86 02 04 86 07 04 86 08 04 86 09 04 86 0f 01 02 10 01 02 11 01 02 13 02 84 14 02 84 18 01 00 19 01 00 27 01 00 fe 02 84
```
- `43`: definition, local 3, global `13 00` = 19 (`lap`), 14 fields: timestamp (253), start_time (2), total_elapsed_time (7),
  total_timer_time (8), total_distance (9), avg_heart_rate (15), max_heart_rate (16), avg_cadence (17),
  avg_power (19), max_power (20), lap_trigger (24, enum), sport (25, enum), sub_sport (39, enum), message_index (254, uint16).

```
DATA local=3 gmn=19 03 22 2b 60 45 20 2b 60 45 b8 0b 00 00 b8 0b 00 00 4a 06 00 00 79 7a 5b d2 00 dc 00 07 02 3a 00 00
```
- Local 3. Timestamp t0+2; start_time t0; total elapsed `b8 0b 00 00` = 3000 (3.000 s); total timer 3000;
  total distance `4a 06 00 00` = 1610 (16.10 m); avg hr `79` = 121; max hr `7a` = 122; avg cadence `5b` = 91;
  avg power `d2 00` = 210; max power `dc 00` = 220; lap_trigger `07` = sessionEnd; sport `02` = cycling;
  sub_sport `3a` = 58 (virtual activity); message_index `00 00` = 0.

```
DEF  local=4 gmn=18 fields=15 44 00 00 12 00 0f fd 04 86 02 04 86 05 01 00 06 01 00 07 04 86 08 04 86 09 04 86 10 01 02 11 01 02 12 01 02 14 02 84 15 02 84 19 02 84 1a 02 84 1c 01 00
```
- `44`: definition, local 4, global `12 00` = 18 (`session`), 15 fields: timestamp (253), start_time (2), sport (5, enum),
  sub_sport (6, enum), total_elapsed_time (7), total_timer_time (8), total_distance (9), avg_heart_rate (16),
  max_heart_rate (17), avg_cadence (18), avg_power (20), max_power (21), first_lap_index (25), num_laps (26),
  trigger (28, enum).

```
DATA local=4 gmn=18 04 22 2b 60 45 20 2b 60 45 02 3a b8 0b 00 00 b8 0b 00 00 4a 06 00 00 79 7a 5b d2 00 dc 00 00 00 01 00 00
```
- Local 4. Timestamp t0+2; start t0; sport `02` (cycling); sub_sport `3a` (58); total elapsed 3000; total timer 3000;
  total distance 1610; avg hr 121; max hr 122; avg cadence 91; avg power 210; max power 220; first_lap_index `00 00` = 0;
  num_laps `01 00` = 1; trigger `00` = activity_end.

```
DEF  local=5 gmn=34 fields=7 45 00 00 22 00 07 fd 04 86 00 04 86 01 02 84 02 01 00 03 01 00 04 01 00 05 04 86
```
- `45`: definition, local 5, global `22 00` = 34 (`activity`), 7 fields: timestamp (253), total_timer_time (0),
  num_sessions (1, uint16), type (2, enum), event (3, enum), event_type (4, enum), local_timestamp (5, uint32).

```
DATA local=5 gmn=34 05 22 2b 60 45 b8 0b 00 00 01 00 00 1a 01 20 2b 60 45
```
- Local 5. Timestamp t0+2; total timer `b8 0b 00 00` = 3000; num_sessions `01 00` = 1; type `00` = manual;
  event `1a` = 26 (activity); event_type `01` = stop; local_timestamp `20 2b 60 45` = t0 (the example is UTC, so local equals UTC).

```
CRC 9c 34
```
- File CRC `0x349C` (little endian), over bytes 0 to 373.

Sum check: 14 header + 360 data + 2 CRC = 376 bytes.

### 6.2 Independent checks

1. Python, written from the CRC-16/ARC definition (bitwise, not the nibble table), gives the same header CRC `0xE18A`
   and file CRC `0x349C`. Running it over the whole file gives 0.
2. A minimal reader (normal headers only) parsed the definitions and data and counted records by global message
   number: `file_id` 1, `event` 2, `record` 3, `lap` 1, `session` 1, `activity` 1. It ended exactly at the CRC.
3. Java SDK oracle (`com.garmin:fit` 21.222.0, `Decoder` listener): decoded all 9 messages with the expected values.
   For example, `record` 2 gave `heart_rate=122 cadence=92 distance=16.1 speed=8.2 power=220 enhanced_speed=8.2`,
   `lap` gave `total_timer_time=3.0 avg_power=210 max_power=220 lap_trigger=7 sport=2 sub_sport=58`, and `activity`
   gave `event=26 event_type=1`.
4. The Java SDK encoder wrote the 14-byte header `0e20e652230000002e464954dfa2` for a one-message `file_id` file.
   Protocol byte `0x20` and profile `0x52E6` are the same as in our file. Data size `0x23` = 35 is a 21-byte
   definition (5 fields) plus a 14-byte data message.

Not checked: the JS SDK decoder on our file (I did not run downloaded JavaScript). Not checked: any real importer
(intervals.icu, Strava).

### 6.3 How the writer uses this example (stage M2b-1)

`FitGoldenTest` feeds the three seconds of the example to `FitWriter` and compares the output with the listing above
byte for byte. The mapping from the ride record that the example implies, and which the writer follows:

- The sample of second *k* is stamped `start + k − 1` (the first record is at the start time), and a lap, the session,
  the activity and the timer-stop event end at the timestamp of their last record (`t0 + 2` here), while the elapsed time
  is the number of seconds (3 s). So `end − start + 1 s = elapsed`. `FitOptions.recordStamp` can stamp the end of the
  second instead.
- One difference, deliberately: the example's `activity.local_timestamp` is `t0` while its `timestamp` is `t0 + 2`. The
  profile defines `local_timestamp` as the timestamp expressed in local time, so readers take the UTC offset to be
  `local_timestamp − timestamp`, which here would be −2 s and for a one-hour ride about −1 h. The writer therefore
  writes `timestamp + utcOffsetSeconds` (`FitOptions.utcOffsetSeconds`, UTC by default), and the golden test passes an
  offset of −2 s to reproduce the document's bytes.
- The example's `lap` and `session` have no speed or maximum-cadence fields and its `session` has no load fields;
  the writer's layout is exactly that, and adds the four load fields (normalised power, TSS, intensity factor, work) to
  the session only when the ride has a normalised power, which a 3 s ride does not.

An independent reader written for the tests (`FitReader`, sharing no code with the writer) and, locally and opt-in,
Garmin's Java decoder (`GarminFitOracleTest`, ADR 0007) both read the files the writer produces: the decoder reports
the files as FIT with a valid integrity check and the same messages and values. That does not replace an import into
intervals.icu (section 8).

## 7. Licence

Source: `LICENSE.txt` in the npm package `@garmin/fitsdk` 21.222.0, read on 2026-11-18. The file is titled
"Flexible and Interoperable Data Transfer (FIT) Protocol License Agreement", with "Last updated: October 12, 2022".
The Maven pom for `com.garmin:fit` names the same title and links `https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt`.
Governing law: Kansas. I did not read other language SDKs' terms separately, but the Java and JS packages cite the
same agreement.

Summary, in my own words:

- **Licensed Technology** is the SDK "that includes documentation describing the FIT protocol and related source
  code files" (preamble). It is not defined as only the code, so the protocol documentation seems to be included.
- **Grant** (§1): a non-exclusive, royalty-free, non-transferable, non-sublicensable licence "for internal business
  purposes", which includes "use the FIT protocol in any software created by Licensee".

(a) **Writing our own encoder from the public format documentation.** The grant names use of the FIT protocol in
our own software, which is the stated purpose. §2(h) requires following the FIT protocol definitions and not
deviating from them. §3 requires keeping interoperability. Nothing I read forbids implementing a published format.
The open point is that the definition of Licensed Technology includes the documentation, so reading it may already
bring us under the agreement (§9 ends it on breach). The terms do not say. **Verdict: the stated grant covers using
the protocol in our software; whether reading the protocol documentation is itself covered is unclear. Ask Garmin.**

(b) **Using the `com.garmin:fit` jar only in unit tests of a public repository (not shipped, not committed).**
The jar is not distributed in the APK, so §2(c) (no distribution to third parties) and §2(d) (no copyleft
distribution) are not triggered by the app. Using it internally for testing fits §1 (internal business purposes).
Publishing the repository with the jar committed would be a distribution, so the jar must stay out of the repo.
Fetching it as a test dependency from Maven Central is internal use only, but the terms do not address it.
**Verdict: probably acceptable as an internal test dependency, not committed and not shipped. Ask Garmin to confirm.**

(c) **Bundling the SDK in the app.** §2(c) forbids distributing the Licensed Technology, or making it available to
third parties, except as the agreement says, and §1 gives no distribution right. §2(d) also forbids distribution in a
way that would subject it to a source-disclosure licence (for example GPL, which the spec rules out). §9: breach ends
the licence. **Verdict: not allowed without Garmin's written permission. Do not bundle it.**

Also relevant: §2(f) forbids using the technology for benchmarking or competitive analysis. A round-trip check in our
tests is not that, but a comparison of writers for market purposes would be. §5: any modifications we make to the SDK
become Garmin's to use (not relevant if we do not modify it).

What the owner should ask Garmin: (1) whether reading the FIT protocol documentation to write an independent
encoder is within §1; (2) whether the `com.garmin:fit` jar may be used as a test dependency in a public repository
(not committed, not shipped); (3) whether Garmin will license distribution of the SDK in an app, and on what terms.

## 8. Open questions (only an import test can settle these)

1. **Protocol version byte.** The Java SDK writes `0x20`, the JS SDK writes `0x02`. Which one intervals.icu and
   Strava accept is not known. Recommendation: write `0x20`, then confirm with a real import.
2. **Sub-sport and sport.** `sub_sport` 58 (virtual activity) with `sport` 2 (cycling) is what the spec asks for.
   Whether intervals.icu and Strava treat it as an indoor/virtual ride, and whether Strava needs `sub_sport` 6
   (indoor cycling) or 58 for a virtual ride upload, is unknown. Also unknown: whether they require GPS.
3. **Speed fields.** Whether intervals.icu and Strava read `speed` (6), `enhanced_speed` (73) or both, and whether
   writing both causes a duplicate. The profile says `speed` has `enhanced_speed` as its component. Test with a file
   that has only `enhanced_speed`, and one with both.
4. **HRV placement.** The profile gives no timestamp for `hrv`. Whether an importer assigns RR intervals to the
   record before, after or at the same second is unknown. Also unknown: whether intervals.icu uses the `hrv` messages
   for rMSSD or ignores them.
5. **Manufacturer 255.** Whether importers accept `development` (255) as a manufacturer, or require a registered
   ID. Garmin's manufacturer list is in the profile, but we have no ID of our own.
6. **Message order and events.** Which of the conventional messages (`event` start/stop_all, final `lap`, `activity`)
   importers need. An activity without `activity` or with no `event` messages may import as a session only.
7. **Distance and speed consistency.** Whether an importer rejects a file where `distance` and integrated speed differ.
   Our synthetic values are consistent; real ride data is not guaranteed to be.
8. **Profile source.** The `profile.js` file is generated from `Profile.xlsx` (its header says so) but the xlsx itself
   and the FIT Protocol PDF were not read. Check the field numbers against the PDF before the writer is merged.
9. **Compressed headers and developer fields.** Not used. Not verified how importers handle them if a later version
   of the writer uses them.

## 9. Reading outdoor rides (stage P3-7, ADR 0026)

The service reads FIT files as well as the tablet writes them: an outdoor ride recorded by a bike computer (the Geoid CC600 through OnelapFit) arrives as a
FIT file (`POST /v1/activities/fit`, spec §11.8). The decoder is `server/src/fit-decode.ts`, our own (section 7: the SDK is not a dependency). It uses
sections 1 to 3 of this note as they stand and adds what a reader needs:

- **Messages read** (global number, fields): `file_id` (0: type 0, time_created 4), `sport` (12: sport 0, sub_sport 1), `session` (18: timestamp 253,
  start_time 2, sport 5, sub_sport 6, total_elapsed_time 7, total_timer_time 8, total_distance 9, total_ascent 22, avg_heart_rate 16, max_heart_rate 17),
  `lap` (19: timestamp 253, start_time 2, total_elapsed_time 7, total_distance 9), `record` (20: timestamp 253, position_lat 0, position_long 1, altitude 2,
  enhanced_altitude 78, heart_rate 3, cadence 4, distance 5, speed 6, enhanced_speed 73, power 7), `activity` (34: num_sessions 1). The field numbers are from
  profile 21.222 (checked as facts, no code copied). Every other message is skipped by its size.
- **Developer fields** (bit 5 of a definition's header): their definitions are read for their sizes and their bytes skipped.
- **Compressed-timestamp headers** (bit 7): the five-bit offset is added to the last full timestamp of any message, with the 32-second rollover of the profile
  (`t = last − (last & 31) + offset`, plus 32 when `offset < (last & 31)`).
- **Invalid values** of a base type (section 2) mean "absent"; arrays of a field we read are skipped; a `date_time` below `0x10000000` is not a date and the
  record is left out.
- **Units:** time in FIT seconds since 1989-12-31 (section 3); distance in 1/100 m; speed in 1/1000 m/s (the 32-bit field wins when both are there); altitude in
  1/5 m with an offset of 500 m; position in semicircles (`degrees = v · 180 / 2³¹`); elapsed time in 1/1000 s.
- **What an outdoor ride is:** the file's cycling sessions (sport 2, or 21 for e-biking); the ride runs from the first session's start to the last session's end
  (`max(timestamp, start + elapsed)`); the distance and climb are the sessions' totals when they say them, else the records' odometer and the altitudes with a 3 m dead
  band. Heart rate is read at the file's own density; a bike computer stops writing records while its timer is stopped.

The tests write FIT files with `server/test/helpers/fit-encoder.ts` (a second, independent encoder: both byte orders, developer fields, compressed timestamps,
multi-session files) and read the tablet writer's own golden file. Open question for a real OnelapFit file: which of these messages and fields it writes.
