/** Minimal well-formed images, built byte by byte so the tests carry no binary files. */

function chunk(type, data = Buffer.alloc(0)) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  return Buffer.concat([len, Buffer.from(type, "latin1"), data, Buffer.alloc(4)]);
}

export function png({ text = true } = {}) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0);
  ihdr.writeUInt32BE(1, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    ...(text ? [chunk("tEXt", Buffer.from("Author\u0000Somebody Private", "latin1"))] : []),
    chunk("IDAT", Buffer.from([1, 2, 3, 4])),
    chunk("IEND"),
  ]);
}

function seg(marker, data) {
  const len = Buffer.alloc(2);
  len.writeUInt16BE(data.length + 2);
  return Buffer.concat([Buffer.from([0xff, marker]), len, data]);
}

export function jpeg({ exif = true } = {}) {
  return Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    seg(0xe0, Buffer.from("JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000", "latin1")),
    ...(exif ? [seg(0xe1, Buffer.from("Exif\u0000\u0000GPS 51.5N 0.1W", "latin1"))] : []),
    seg(0xda, Buffer.from([1, 2, 3, 4, 5, 6])),
    Buffer.from([0x11, 0x22, 0x33]),
    Buffer.from([0xff, 0xd9]),
  ]);
}

export const b64 = (buf) => buf.toString("base64");
