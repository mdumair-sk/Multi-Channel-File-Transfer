#!/usr/bin/env python3
"""
Enhanced Async Dual-Channel Server v3 - FIXED
- Fixed field name mismatch: using "chunk_size" to match Android client
- Handles session_start, chunk_header (+raw bytes), transfer_complete
- Verifies SHA-256 checksums from Android client
"""

import asyncio
import struct
import hashlib
import json
import time
import logging
import os
from dataclasses import dataclass, field
from typing import Dict, Set

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("serverV3")

@dataclass
class TransferSession:
    transfer_id: str
    filename: str
    total_chunks: int
    chunk_size: int
    chunks: Dict[int, bytes] = field(default_factory=dict)
    start_time: float = field(default_factory=time.time)

class ProtocolHandler:
    MAGIC_HEADER = b'\x42\x54\x46\x53'
    VERSION = 1
    MAX_MESSAGE_SIZE = 2 * 1024 * 1024

    @staticmethod
    def serialize_message(msg_type: str, data: dict) -> bytes:
        json_data = json.dumps(data, separators=(',', ':')).encode('utf-8')
        msg_type_bytes = msg_type.encode('utf-8')
        header = struct.pack("!4sBH", ProtocolHandler.MAGIC_HEADER, ProtocolHandler.VERSION, len(msg_type_bytes))
        data_header = struct.pack("!I", len(json_data))
        checksum = hashlib.sha256(msg_type_bytes + json_data).digest()
        return header + msg_type_bytes + data_header + json_data + checksum

    @staticmethod
    def deserialize_message(data: bytes) -> tuple[str, dict]:
        magic, version, msg_type_len = struct.unpack("!4sBH", data[:7])
        if magic != ProtocolHandler.MAGIC_HEADER:
            raise ValueError("Invalid magic header")
        if version != ProtocolHandler.VERSION:
            raise ValueError("Unsupported version")
        offset = 7
        msg_type = data[offset:offset + msg_type_len].decode("utf-8")
        offset += msg_type_len
        data_len = struct.unpack("!I", data[offset:offset + 4])[0]
        offset += 4
        json_data = data[offset:offset + data_len]
        offset += data_len
        checksum = data[offset:offset + 32]
        expected = hashlib.sha256(msg_type.encode("utf-8") + json_data).digest()
        if checksum != expected:
            raise ValueError("Checksum mismatch")
        return msg_type, json.loads(json_data.decode("utf-8"))

class EnhancedAsyncDualChannelServer:
    def __init__(self, usb_port=8888, wifi_port=8889, output_dir="./received"):
        self.usb_port = usb_port
        self.wifi_port = wifi_port
        self.output_dir = output_dir
        os.makedirs(output_dir, exist_ok=True)
        self.transfers: Dict[str, TransferSession] = {}

    async def read_exact(self, reader: asyncio.StreamReader, num_bytes: int, timeout: float = 30.0) -> bytes:
        data = b''
        while len(data) < num_bytes:
            chunk = await asyncio.wait_for(reader.read(num_bytes - len(data)), timeout=timeout)
            if not chunk:
                raise asyncio.IncompleteReadError(data, num_bytes)
            data += chunk
        return data

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, channel_type: str):
        client_addr = writer.get_extra_info("peername")
        connection_id = f"{channel_type}_{client_addr[0]}_{client_addr[1]}_{int(time.time())}"
        logger.info(f"🔗 {channel_type} client connected from {client_addr} [{connection_id}]")

        try:
            while True:
                # --- Header parsing ---
                header7 = await self.read_exact(reader, 7, timeout=60.0)
                magic, version, msg_type_len = struct.unpack("!4sBH", header7)
                if magic != ProtocolHandler.MAGIC_HEADER:
                    logger.error("Invalid magic header")
                    break

                remaining_header = await self.read_exact(reader, msg_type_len + 4, timeout=10.0)
                data_len = struct.unpack("!I", remaining_header[msg_type_len:msg_type_len + 4])[0]
                if data_len > ProtocolHandler.MAX_MESSAGE_SIZE:
                    logger.error(f"Message too large: {data_len}")
                    break

                remaining_data = await self.read_exact(reader, data_len + 32, timeout=60.0)
                full_message = header7 + remaining_header + remaining_data
                msg_type, msg_data = ProtocolHandler.deserialize_message(full_message)
                logger.info(f"📨 {channel_type} received {msg_type}")

                # --- Handle messages ---
                if msg_type == "session_start":
                    session = TransferSession(
                        transfer_id=msg_data["transfer_id"],
                        filename=msg_data["filename"],
                        total_chunks=msg_data["total_chunks"],
                        chunk_size=msg_data["chunk_size"],
                    )
                    self.transfers[session.transfer_id] = session
                    ack = ProtocolHandler.serialize_message("session_ack", {
                        "transfer_id": session.transfer_id, "status": "ready"
                    })
                    writer.write(ack)
                    await writer.drain()
                    logger.info(f"✅ {channel_type} session started for {session.filename}")

                elif msg_type == "chunk_header":
                    tid = msg_data["transfer_id"]
                    chunk_id = msg_data["chunk_id"]
                    # FIXED: Use "chunk_size" instead of "size" to match Android client
                    chunk_size = msg_data["chunk_size"]
                    checksum = msg_data["checksum"]

                    # read raw chunk immediately
                    raw_chunk = await self.read_exact(reader, chunk_size, timeout=30.0)
                    calc = hashlib.sha256(raw_chunk).hexdigest()
                    if calc != checksum:
                        logger.error(f"❌ {channel_type} checksum mismatch on chunk {chunk_id}")
                        logger.error(f"Expected: {checksum}, Got: {calc}")
                        continue

                    if tid in self.transfers:
                        self.transfers[tid].chunks[chunk_id] = raw_chunk

                    ack = ProtocolHandler.serialize_message("chunk_ack", {
                        "transfer_id": tid, "chunk_id": chunk_id, "status": "ok"
                    })
                    writer.write(ack)
                    await writer.drain()
                    logger.info(f"✅ Stored chunk {chunk_id} ({len(raw_chunk)} bytes) from {channel_type}")

                elif msg_type == "transfer_complete":
                    tid = msg_data["transfer_id"]
                    if tid in self.transfers:
                        session = self.transfers[tid]
                        file_path = os.path.join(self.output_dir, session.filename)

                        # Write chunks in order
                        with open(file_path, "wb") as f:
                            for i in range(session.total_chunks):
                                if i in session.chunks:
                                    f.write(session.chunks[i])
                                else:
                                    logger.warning(f"Missing chunk {i} for transfer {tid}")

                        duration = int((time.time() - session.start_time) * 1000)
                        file_size = os.path.getsize(file_path)
                        logger.info(f"🎉 Transfer {tid} complete!")
                        logger.info(f"   File: {file_path}")
                        logger.info(f"   Size: {file_size} bytes")
                        logger.info(f"   Duration: {duration} ms")
                        logger.info(f"   Chunks received: {len(session.chunks)}/{session.total_chunks}")

                        # Clean up transfer data
                        del self.transfers[tid]

                    ack = ProtocolHandler.serialize_message("transfer_ack", {
                        "transfer_id": tid, "status": "done"
                    })
                    writer.write(ack)
                    await writer.drain()

        except asyncio.IncompleteReadError as e:
            logger.warning(f"{channel_type} connection closed by client")
        except Exception as e:
            logger.error(f"{channel_type} error: {e}")
            import traceback
            logger.error(traceback.format_exc())
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
            logger.info(f"🔒 {channel_type} [{connection_id}] connection closed")

    async def start_server(self):
        logger.info("🚀 Starting Enhanced Async Dual-Channel Server...")
        usb_server = await asyncio.start_server(
            lambda r, w: self.handle_client(r, w, "USB"),
            "127.0.0.1",
            self.usb_port
        )
        wifi_server = await asyncio.start_server(
            lambda r, w: self.handle_client(r, w, "WiFi"),
            "0.0.0.0",
            self.wifi_port
        )

        logger.info(f"🔌 USB Server: 127.0.0.1:{self.usb_port}")
        logger.info(f"📡 WiFi Server: 0.0.0.0:{self.wifi_port}")
        logger.info(f"📁 Output directory: {os.path.abspath(self.output_dir)}")
        logger.info("✅ Server ready for connections")

        async with usb_server, wifi_server:
            await asyncio.gather(usb_server.serve_forever(), wifi_server.serve_forever())

async def main():
    server = EnhancedAsyncDualChannelServer()
    await server.start_server()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("🛑 Server stopped")
