import { describe, expect, it } from "vitest";
import { RouteError } from "../src/routes/geometry.ts";
import { GPX_MAX_BYTES, parseGpx } from "../src/routes/gpx.ts";

/** The tolerant GPX reader of our own (ADR 0036): what it takes, and what it refuses with a plain message. */

const gpx = (
  body: string,
  root = '<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">',
): string => `<?xml version="1.0" encoding="UTF-8"?>\n${root}\n${body}\n</gpx>`;

const point = (lat: number, lon: number, ele?: number): string =>
  `<trkpt lat="${lat}" lon="${lon}">${ele === undefined ? "" : `<ele>${ele}</ele>`}<time>2026-11-19T09:00:00Z</time></trkpt>`;

const track = (...points: string[]): string =>
  `<trk><name>Test</name><trkseg>${points.join("")}</trkseg></trk>`;

function refusal(input: string): string {
  try {
    parseGpx(input);
  } catch (error) {
    expect(error).toBeInstanceOf(RouteError);
    return (error as Error).message;
  }
  throw new Error("expected the GPX to be refused");
}

describe("a plain GPX", () => {
  it("reads the points of a track with their elevation", () => {
    const parsed = parseGpx(
      gpx(track(point(45, 7, 250), point(45.001, 7.001, 251.5), point(45.002, 7.002, 249))),
    );
    expect(parsed.from).toBe("track");
    expect(parsed.segments).toBe(1);
    expect(parsed.track).toEqual([
      { lat: 45, lon: 7, ele: 250 },
      { lat: 45.001, lon: 7.001, ele: 251.5 },
      { lat: 45.002, lon: 7.002, ele: 249 },
    ]);
  });

  it("accepts either quote, spaces around the equals sign, tags over several lines and a byte order mark", () => {
    const text = `﻿${gpx(
      `<trk><trkseg>
        <trkpt
          lat = '45.5'
          lon="7.25" >
          <ele>
            300
          </ele>
        </trkpt>
        <trkpt lat="45.6" lon='7.26'><ele>310</ele></trkpt>
      </trkseg></trk>`,
    )}`;
    expect(parseGpx(text).track).toEqual([
      { lat: 45.5, lon: 7.25, ele: 300 },
      { lat: 45.6, lon: 7.26, ele: 310 },
    ]);
  });

  it("reads negative and exponent numbers, and a negative elevation", () => {
    const parsed = parseGpx(gpx(track(point(-33.5, -70.25, -12.5), point(-33.6, -70.3, 1e2))));
    expect(parsed.track[0]).toEqual({ lat: -33.5, lon: -70.25, ele: -12.5 });
    expect(parsed.track[1]?.ele).toBe(100);
  });
});

describe("namespaces and the rest of a real file", () => {
  it("reads a prefixed namespace", () => {
    const text = `<?xml version="1.0"?><g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk><g:trkseg>
      <g:trkpt lat="45" lon="7"><g:ele>100</g:ele></g:trkpt>
      <g:trkpt lat="45.001" lon="7"><g:ele>101</g:ele></g:trkpt>
    </g:trkseg></g:trk></g:gpx>`;
    expect(parseGpx(text).track).toHaveLength(2);
  });

  it("ignores extensions inside a point, waypoints, metadata, comments, processing instructions and CDATA", () => {
    const text = gpx(
      `<metadata><name>Not a point</name></metadata>
       <wpt lat="10" lon="10"><ele>5000</ele><name>Summit</name></wpt>
       <!-- <trkpt lat="1" lon="1"><ele>1</ele></trkpt> -->
       <![CDATA[ <trkpt lat="2" lon="2"><ele>2</ele></trkpt> ]]>
       <?some-tool instruction?>
       <trk><trkseg>
         <trkpt lat="45" lon="7"><ele>100</ele><extensions><gpxtpx:TrackPointExtension xmlns:gpxtpx="x"><gpxtpx:hr>140</gpxtpx:hr><gpxtpx:atemp>20</gpxtpx:atemp></gpxtpx:TrackPointExtension></extensions></trkpt>
         <trkpt lat="45.001" lon="7"><extensions><n:ele xmlns:n="y">9999</n:ele></extensions><ele>101</ele></trkpt>
       </trkseg></trk>`,
      '<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="x">',
    );
    // The first <ele> inside a point is its elevation; an extension's own is not looked for before it.
    const parsed = parseGpx(text);
    expect(parsed.track).toHaveLength(2);
    expect(parsed.track[0]?.ele).toBe(100);
    expect(parsed.track[1]?.ele).toBe(9999);
  });

  it("joins several tracks and segments in the order of the file", () => {
    const text = gpx(
      `<trk><trkseg>${point(45, 7, 100)}${point(45.001, 7, 101)}</trkseg><trkseg>${point(45.01, 7, 110)}${point(45.011, 7, 111)}</trkseg></trk>
       <trk><trkseg>${point(45.02, 7, 120)}</trkseg></trk>`,
    );
    const parsed = parseGpx(text);
    expect(parsed.segments).toBe(3);
    expect(parsed.track.map((p) => p.ele)).toEqual([100, 101, 110, 111, 120]);
  });

  it("falls back to the points of a planned route when there is no track", () => {
    const text = gpx(
      `<rte><name>Planned</name><rtept lat="45" lon="7"><ele>100</ele></rtept><rtept lat="45.001" lon="7"><ele>102</ele></rtept></rte>`,
    );
    const parsed = parseGpx(text);
    expect(parsed.from).toBe("route");
    expect(parsed.track).toHaveLength(2);
  });

  it("prefers the track to a route in the same file", () => {
    const text = gpx(
      `<rte><rtept lat="1" lon="1"><ele>1</ele></rtept><rtept lat="1.001" lon="1"><ele>1</ele></rtept></rte>${track(point(45, 7, 100), point(45.001, 7, 101))}`,
    );
    expect(parseGpx(text).from).toBe("track");
  });

  it("takes a point whose end tag is missing as ending where the next starts", () => {
    const text = gpx(
      `<trk><trkseg><trkpt lat="45" lon="7"><ele>100</ele><trkpt lat="45.001" lon="7"><ele>101</ele></trkpt></trkseg></trk>`,
    );
    expect(parseGpx(text).track.map((p) => p.ele)).toEqual([100, 101]);
  });
});

describe("a point with no elevation", () => {
  it("is refused, naming how many and the first, and does not guess", () => {
    const message = refusal(
      gpx(track(point(45, 7, 100), point(45.001, 7), point(45.002, 7, 102), point(45.003, 7))),
    );
    expect(message).toMatch(/2 of 4 points of the GPX have no <ele>/);
    expect(message).toMatch(/the first is point 2/);
    expect(message).toMatch(/does not guess/);
    expect(message).toMatch(/waypoints/);
  });

  it("is refused when no point has one, and for a self-closing point", () => {
    expect(refusal(gpx(track(point(45, 7), point(45.001, 7))))).toMatch(/2 of 2 points/);
    expect(
      refusal(
        gpx(`<trk><trkseg><trkpt lat="45" lon="7"/><trkpt lat="45.001" lon="7"/></trkseg></trk>`),
      ),
    ).toMatch(/2 of 2 points/);
  });

  it("is refused when the elevation is empty or not a number", () => {
    expect(
      refusal(
        gpx(
          `<trk><trkseg><trkpt lat="45" lon="7"><ele></ele></trkpt><trkpt lat="45.1" lon="7"><ele>1</ele></trkpt></trkseg></trk>`,
        ),
      ),
    ).toMatch(/point 1 of the GPX has an <ele> that is not a number/);
    expect(
      refusal(
        gpx(
          `<trk><trkseg><trkpt lat="45" lon="7"><ele>12 m</ele></trkpt><trkpt lat="45.1" lon="7"><ele>1</ele></trkpt></trkseg></trk>`,
        ),
      ),
    ).toMatch(/not a number/);
  });
});

describe("what is not a usable GPX", () => {
  it("a coordinate that is missing, not a number or out of range", () => {
    expect(refusal(gpx(track('<trkpt lat="45"><ele>1</ele></trkpt>', point(45.1, 7, 1))))).toMatch(
      /point 1 of the GPX has no usable lat and lon/,
    );
    expect(
      refusal(gpx(track(point(45, 7, 1), '<trkpt lat="north" lon="7"><ele>1</ele></trkpt>'))),
    ).toMatch(/point 2 of the GPX has no usable lat and lon/);
    expect(refusal(gpx(track(point(91, 7, 1), point(45, 7, 1))))).toMatch(
      /latitude outside -90 to 90/,
    );
    expect(refusal(gpx(track(point(45, 181, 1), point(45, 7, 1))))).toMatch(
      /longitude outside -180 to 180/,
    );
  });

  it("a file that is not GPX, empty, or without points", () => {
    expect(refusal("<html><body>hello</body></html>")).toMatch(/does not look like a GPX/);
    expect(refusal("")).toMatch(/does not look like a GPX/);
    expect(refusal("just some text")).toMatch(/does not look like a GPX/);
    expect(refusal(gpx(`<wpt lat="45" lon="7"><ele>100</ele></wpt>`))).toMatch(
      /no track or route points/,
    );
    expect(refusal(gpx(track(point(45, 7, 100))))).toMatch(/single point/);
  });

  it("a file that is cut off", () => {
    expect(refusal('<gpx><trk><trkseg><trkpt lat="45" lon="7')).toMatch(/never closed/);
    expect(refusal("<gpx><!-- a comment that goes on")).toMatch(/comment that is never closed/);
    expect(refusal("<gpx><![CDATA[ never ends")).toMatch(/CDATA section that is never closed/);
    expect(refusal("<gpx><?pi never ends")).toMatch(/processing instruction that is never closed/);
  });
});

describe("hostile files", () => {
  it("refuses a DOCTYPE and any entity declaration without expanding anything", () => {
    const bomb = `<?xml version="1.0"?>
      <!DOCTYPE lolz [
        <!ENTITY lol "lol">
        <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
        <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
      ]>
      <gpx><trk><trkseg><trkpt lat="45" lon="7"><ele>&lol3;</ele></trkpt><trkpt lat="45.1" lon="7"><ele>1</ele></trkpt></trkseg></trk></gpx>`;
    expect(refusal(bomb)).toMatch(/DOCTYPE or entity declaration/);
    const external = `<!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx/>`;
    expect(refusal(external)).toMatch(/DOCTYPE or entity declaration/);
    expect(refusal(`<gpx><!ENTITY x "y"></gpx>`)).toMatch(/DOCTYPE or entity declaration/);
  });

  it("does not expand an entity that is used without being declared: the elevation is simply not a number", () => {
    const text = gpx(track(`<trkpt lat="45" lon="7"><ele>&amp;1</ele></trkpt>`, point(45.1, 7, 1)));
    expect(refusal(text)).toMatch(/not a number/);
  });

  it("refuses a file over 2 MB at once, by bytes and not characters", () => {
    expect(GPX_MAX_BYTES).toBe(2 * 1024 * 1024);
    const filler = "x".repeat(GPX_MAX_BYTES + 1);
    expect(refusal(gpx(`<!-- ${filler} -->`))).toMatch(/larger than 2 MB/);
    // 700,000 three-byte characters are 2.1 MB although only 700,000 characters long.
    expect(refusal(gpx(`<!-- ${"€".repeat(700_000)} -->`))).toMatch(/larger than 2 MB/);
  });

  it("reads a large file that is under the limit in well under a second", () => {
    // About 1.9 MB: 32,000 points of about 60 bytes.
    const points: string[] = [];
    for (let i = 0; i < 32_000; i++) {
      points.push(
        `<trkpt lat="${(45 + i * 0.00001).toFixed(6)}" lon="7.000000"><ele>${100 + (i % 50)}</ele></trkpt>`,
      );
    }
    const text = gpx(track(...points));
    expect(Buffer.byteLength(text)).toBeLessThan(GPX_MAX_BYTES);
    expect(Buffer.byteLength(text)).toBeGreaterThan(1_500_000);
    const started = performance.now();
    const parsed = parseGpx(text);
    expect(parsed.track).toHaveLength(32_000);
    expect(performance.now() - started).toBeLessThan(1500);
  });

  it("is not made slow by a flood of tags that never close or quotes that never end", () => {
    const started = performance.now();
    expect(refusal(`<gpx>${'<a "'.repeat(300_000)}`)).toMatch(/never closed/);
    expect(refusal(`<gpx>${"<".repeat(1_000_000)}`)).toMatch(/never closed|empty tag/);
    expect(performance.now() - started).toBeLessThan(1500);
  });

  it("a tag with a quoted greater-than sign is one tag", () => {
    const text = gpx(
      `<trk><trkseg><trkpt lat="45" lon="7" note="a > b"><ele>100</ele></trkpt><trkpt lat="45.001" lon="7"><ele>101</ele></trkpt></trkseg></trk>`,
    );
    expect(parseGpx(text).track).toHaveLength(2);
  });
});
