"""A minimal Bluetooth SDP client (stdlib only): finds the RFCOMM channel a device serves a UUID on.

Android registers an SDP record for the Tandem app's RFCOMM server (listenUsingRfcommWithServiceRecord),
so the laptop asks the phone's SDP server (L2CAP PSM 1) for that UUID and reads the channel out of the
record's ProtocolDescriptorList.
"""
import socket
import struct
import uuid as uuidlib

SDP_PSM = 1
PDU_SEARCH_ATTR_REQ, PDU_SEARCH_ATTR_RSP, PDU_ERROR = 0x06, 0x07, 0x01
ATTR_PROTOCOL_DESCRIPTORS = 0x0004
UUID_RFCOMM = 0x0003


def _des(body):
    """A data element sequence with a 1- or 2-byte length."""
    if len(body) < 256:
        return bytes([0x35, len(body)]) + body
    return bytes([0x36]) + struct.pack(">H", len(body)) + body


def _request(uuid128, tid, cont):
    pattern = _des(bytes([0x1C]) + uuidlib.UUID(uuid128).bytes)
    attrs = _des(bytes([0x09]) + struct.pack(">H", ATTR_PROTOCOL_DESCRIPTORS))
    params = pattern + struct.pack(">H", 0xFFFF) + attrs + bytes([len(cont)]) + cont
    return struct.pack(">BHH", PDU_SEARCH_ATTR_REQ, tid, len(params)) + params


def _parse(buf, i=0):
    """One data element at buf[i]: (value, next index). Sequences become lists; UUIDs become ints."""
    d = buf[i]
    kind, size = d >> 3, d & 7
    i += 1
    if kind == 0:
        return None, i
    if size < 5:
        n = (1, 2, 4, 8, 16)[size]
    else:
        width = (1, 2, 4)[size - 5]
        n = int.from_bytes(buf[i:i + width], "big")
        i += width
    raw = buf[i:i + n]
    end = i + n
    if kind in (1, 2, 3):  # uint, int, UUID
        return int.from_bytes(raw, "big", signed=kind == 2), end
    if kind in (6, 7):  # sequence, alternative
        out, j = [], i
        while j < end:
            v, j = _parse(buf, j)
            out.append(v)
        return out, end
    if kind == 5:
        return bool(raw[0]), end
    return bytes(raw), end


def rfcomm_channel(addr, uuid128, timeout=8.0):
    """The RFCOMM channel `addr` serves `uuid128` on, or None if it doesn't. Raises OSError when the
    device can't be reached (off, out of range)."""
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    s.settimeout(timeout)
    try:
        s.connect((addr, SDP_PSM))
        data, cont, tid = b"", b"", 1
        for _ in range(32):  # continuation rounds
            s.send(_request(uuid128, tid, cont))
            rsp = s.recv(4096)
            pdu, _, plen = struct.unpack(">BHH", rsp[:5])
            if pdu != PDU_SEARCH_ATTR_RSP:
                return None
            count = struct.unpack(">H", rsp[5:7])[0]
            data += rsp[7:7 + count]
            clen = rsp[7 + count]
            cont = rsp[8 + count:8 + count + clen]
            tid += 1
            if not cont:
                break
    finally:
        s.close()
    if not data:
        return None
    records, _ = _parse(data)
    for rec in records or []:
        # rec = [attr id, value, attr id, value, ...]
        for k in range(0, len(rec) - 1, 2):
            if rec[k] != ATTR_PROTOCOL_DESCRIPTORS or not isinstance(rec[k + 1], list):
                continue
            for proto in rec[k + 1]:
                if isinstance(proto, list) and len(proto) >= 2 and proto[0] == UUID_RFCOMM:
                    return int(proto[1])
    return None


if __name__ == "__main__":
    import sys
    print(rfcomm_channel(sys.argv[1], sys.argv[2]))
