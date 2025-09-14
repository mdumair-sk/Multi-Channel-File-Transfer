#!/usr/bin/env python3
"""
Enhanced Async Dual-Channel Server v3 - MEMORY OPTIMIZED
- Writes chunks directly to disk instead of storing in memory
- Uses file.seek() for random chunk positioning
- Tracks received chunks in a Set for 90%+ memory reduction
- Handles sparse files by padding with zeros
- Maintains identical protocol compatibility
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
    received_chunks: Set[int] = field(default_factory=set)  # Track which chunks we have
    start_time: float = field(default_factory=time.time)
    file_handle: object = None  # Will store the open file handle
    file_path: str = ""  # Full path to the output file

    def __post_init__(self):
        """Initialize the output file when session is created"""
        # This will be called by the server after setting file_path
        pass

    def open_file_for_writing(self, output_dir: str):
        """Open the output file and pre-allocate space if possible"""
        self.file_path = os.path.join(output_dir, self.filename)

        # Create file first, then open in read-write mode
        # Use 'w+b' to create file if it doesn't exist, then truncate to 0
        self.file_handle = open(self.file_path, 'w+b')

        # Pre-allocate file space by writing zeros at the end
        expected_size = self.total_chunks * self.chunk_size
        if expected_size > 0:
            self.file_handle.seek(expected_size - 1)
            self.file_handle.write(b'\x00')  # Write one byte at the end
            self.file_handle.flush()

        logger.info(f"📁 Created and pre-allocated {expected_size:,} bytes for {self.filename}")
        logger.info(f"📂 File path: {self.file_path}")

    def write_chunk(self, chunk_id: int, chunk_data: bytes):
        """Write chunk directly to disk at the correct position"""
        if self.file_handle is None:
            raise RuntimeError(f"File handle not initialized for transfer {self.transfer_id}")

        # Calculate file position for this chunk
        file_position = chunk_id * self.chunk_size

        # Seek to correct position and write chunk
        self.file_handle.seek(file_position)
        bytes_written = self.file_handle.write(chunk_data)
        self.file_handle.flush()  # Ensure data is written to disk

        # Track that we received this chunk
        self.received_chunks.add(chunk_id)

        logger.debug(f"📝 Wrote chunk {chunk_id} at position {file_position} ({bytes_written} bytes)")
        return bytes_written

    def is_complete(self) -> bool:
        """Check if all chunks have been received"""
        return len(self.received_chunks) == self.total_chunks

    def get_missing_chunks(self) -> Set[int]:
        """Return set of missing chunk IDs"""
        all_chunks = set(range(self.total_chunks))
        return all_chunks - self.received_chunks

    def finalize_file(self):
        """Close file handle and handle any missing chunks"""
        if self.file_handle is None:
            return

        missing_chunks = self.get_missing_chunks()

        if missing_chunks:
            logger.warning(f"⚠️  Transfer {self.transfer_id} has {len(missing_chunks)} missing chunks")
            logger.warning(f"Missing chunks: {sorted(list(missing_chunks))[:10]}{'...' if len(missing_chunks) > 10 else ''}")

            # Fill missing chunks with zeros (sparse file handling)
            for chunk_id in missing_chunks:
                file_position = chunk_id * self.chunk_size
                self.file_handle.seek(file_position)

                # For the last chunk, only write the remaining bytes
                if chunk_id == self.total_chunks - 1:
                    # Calculate actual size of last chunk
                    remaining_bytes = self.chunk_size  # Default to full chunk
                    # Note: We don't have the exact file size here, so we pad full chunk

                zeros_to_write = self.chunk_size
                self.file_handle.write(b'\x00' * zeros_to_write)
                logger.info(f"🔧 Filled missing chunk {chunk_id} with zeros")

        # Close the file handle
        self.file_handle.close()
        self.file_handle = None

        # Get final file size
        actual_size = os.path.getsize(self.file_path)
        logger.info(f"📊 File {self.filename} finalized: {actual_size} bytes")

    def cleanup(self):
        """Clean up resources"""
        if self.file_handle is not None:
            try:
                self.file_handle.close()
            except:
                pass
            self.file_handle = None

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

class DualChannelServer:
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

                    # Initialize the file for writing
                    try:
                        session.open_file_for_writing(self.output_dir)
                        self.transfers[session.transfer_id] = session

                        ack = ProtocolHandler.serialize_message("session_ack", {
                            "transfer_id": session.transfer_id, "status": "ready"
                        })
                        writer.write(ack)
                        await writer.drain()
                        logger.info(f"✅ {channel_type} session started for {session.filename}")
                        logger.info(f"💾 Memory usage: Storing only chunk tracking set instead of {session.total_chunks * session.chunk_size} bytes")

                    except Exception as e:
                        logger.error(f"❌ Failed to initialize file for {session.filename}: {e}")
                        ack = ProtocolHandler.serialize_message("session_ack", {
                            "transfer_id": session.transfer_id, "status": "error", "message": str(e)
                        })
                        writer.write(ack)
                        await writer.drain()

                elif msg_type == "chunk_header":
                    tid = msg_data["transfer_id"]
                    chunk_id = msg_data["chunk_id"]
                    chunk_size = msg_data["chunk_size"]
                    checksum = msg_data["checksum"]

                    # Read raw chunk data immediately
                    raw_chunk = await self.read_exact(reader, chunk_size, timeout=30.0)

                    # Verify checksum
                    calc_checksum = hashlib.sha256(raw_chunk).hexdigest()
                    if calc_checksum != checksum:
                        logger.error(f"❌ {channel_type} checksum mismatch on chunk {chunk_id}")
                        logger.error(f"Expected: {checksum}, Got: {calc_checksum}")
                        continue

                    # Write chunk directly to disk instead of storing in memory
                    if tid in self.transfers:
                        session = self.transfers[tid]
                        try:
                            bytes_written = session.write_chunk(chunk_id, raw_chunk)

                            # Send acknowledgment
                            ack = ProtocolHandler.serialize_message("chunk_ack", {
                                "transfer_id": tid,
                                "chunk_id": chunk_id,
                                "status": "ok",
                                "bytes_written": bytes_written
                            })
                            writer.write(ack)
                            await writer.drain()

                            # Log progress every 50 chunks
                            if len(session.received_chunks) % 50 == 0:
                                progress = (len(session.received_chunks) / session.total_chunks) * 100
                                logger.info(f"📈 {channel_type} progress: {len(session.received_chunks)}/{session.total_chunks} chunks ({progress:.1f}%)")

                        except Exception as e:
                            logger.error(f"❌ Failed to write chunk {chunk_id}: {e}")
                            ack = ProtocolHandler.serialize_message("chunk_ack", {
                                "transfer_id": tid,
                                "chunk_id": chunk_id,
                                "status": "error",
                                "message": str(e)
                            })
                            writer.write(ack)
                            await writer.drain()
                    else:
                        logger.warning(f"⚠️  Received chunk for unknown transfer {tid}")

                elif msg_type == "transfer_complete":
                    tid = msg_data["transfer_id"]
                    if tid in self.transfers:
                        session = self.transfers[tid]

                        try:
                            # Finalize the file (handle missing chunks, close file handle)
                            session.finalize_file()

                            # Calculate statistics
                            duration = int((time.time() - session.start_time) * 1000)
                            file_size = os.path.getsize(session.file_path)
                            chunks_received = len(session.received_chunks)

                            # Report results
                            logger.info(f"🎉 Transfer {tid} complete!")
                            logger.info(f"   File: {session.file_path}")
                            logger.info(f"   Size: {file_size:,} bytes")
                            logger.info(f"   Duration: {duration} ms")
                            logger.info(f"   Chunks: {chunks_received}/{session.total_chunks}")
                            logger.info(f"   Success rate: {(chunks_received/session.total_chunks)*100:.1f}%")

                            if chunks_received == session.total_chunks:
                                logger.info(f"✅ Perfect transfer - all chunks received!")

                            # Memory usage report
                            estimated_old_memory = session.total_chunks * session.chunk_size
                            actual_memory_used = len(session.received_chunks) * 8  # Set of integers
                            memory_saved = estimated_old_memory - actual_memory_used
                            savings_percent = (memory_saved / estimated_old_memory) * 100

                            logger.info(f"💾 Memory saved: {memory_saved:,} bytes ({savings_percent:.1f}% reduction)")

                        except Exception as e:
                            logger.error(f"❌ Error finalizing transfer {tid}: {e}")

                        finally:
                            # Clean up transfer session
                            session.cleanup()
                            del self.transfers[tid]

                    # Send acknowledgment
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
            # Clean up any remaining transfers for this connection
            transfers_to_cleanup = []
            for tid, session in self.transfers.items():
                if session.file_handle is not None:
                    transfers_to_cleanup.append(tid)

            for tid in transfers_to_cleanup:
                logger.info(f"🧹 Cleaning up incomplete transfer {tid}")
                self.transfers[tid].cleanup()
                del self.transfers[tid]

            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
            logger.info(f"🔒 {channel_type} [{connection_id}] connection closed")

    async def start_server(self):
        logger.info("🚀 Starting Dual-Channel Server ...")
        logger.info("💾 This version writes chunks directly to disk for 90%+ memory reduction")

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
    server = DualChannelServer()
    await server.start_server()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("🛑 Server stopped")