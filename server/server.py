#!/usr/bin/env python3
"""
Enhanced Async Dual-Channel Server v3 - MEMORY OPTIMIZED with ADB Support
- Writes chunks directly to disk instead of storing in memory
- Uses file.seek() for random chunk positioning
- Tracks received chunks in a Set for 90%+ memory reduction
- Handles sparse files by padding with zeros
- Maintains identical protocol compatibility
- Cross-platform ADB support for automatic USB port forwarding
"""

import asyncio
import struct
import hashlib
import json
import time
import logging
import os
import platform
import shutil
import subprocess
from dataclasses import dataclass, field
from typing import Dict, Set, List, Optional

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger("server")


class ADBManager:
    """Handles ADB operations for automatic port forwarding setup with cross-platform support"""

    def __init__(self, usb_port: int = 8888):
        self.usb_port = usb_port
        self.system = platform.system()
        self.adb_path = self._find_adb()
        self.connected_devices: List[str] = []

        if self.adb_path:
            logger.info(f"📱 ADB found at: {self.adb_path}")

    def _get_platform_adb_paths(self) -> List[str]:
        """Get platform-specific ADB paths"""
        paths = ["adb"]  # Always check PATH first

        if self.system == "Windows":
            paths.extend(
                [
                    "adb.exe",
                    os.path.expanduser(
                        "~/AppData/Local/Android/Sdk/platform-tools/adb.exe"
                    ),
                    "C:/Android/Sdk/platform-tools/adb.exe",
                    "C:/Program Files/Android/Android Studio/sdk/platform-tools/adb.exe",
                    "C:/Program Files (x86)/Android/Android Studio/sdk/platform-tools/adb.exe",
                    os.path.expanduser("~/Android/Sdk/platform-tools/adb.exe"),
                ]
            )
        elif self.system == "Darwin":  # macOS
            paths.extend(
                [
                    "/usr/local/bin/adb",
                    "/opt/homebrew/bin/adb",  # Apple Silicon Homebrew
                    os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
                    os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
                    "/Applications/Android Studio.app/Contents/sdk/platform-tools/adb",
                ]
            )
        elif self.system == "Linux":
            paths.extend(
                [
                    "/usr/bin/adb",
                    "/usr/local/bin/adb",
                    "/snap/bin/adb",  # Snap package
                    "/opt/android-sdk/platform-tools/adb",
                    os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
                    os.path.expanduser("~/android-sdk/platform-tools/adb"),
                    os.path.expanduser("~/.local/share/android-sdk/platform-tools/adb"),
                ]
            )

        return paths

    def _find_adb(self) -> Optional[str]:
        """Find ADB executable across different platforms"""
        possible_paths = self._get_platform_adb_paths()

        for path in possible_paths:
            if shutil.which(path) or os.path.isfile(path):
                if self._verify_adb(path):
                    return path
        return None

    def _verify_adb(self, adb_path: str) -> bool:
        """Verify that the found executable is actually ADB"""
        try:
            result = subprocess.run(
                [adb_path, "version"], capture_output=True, text=True, timeout=5
            )
            return result.returncode == 0 and "Android Debug Bridge" in result.stdout
        except:
            return False

    def is_available(self) -> bool:
        """Check if ADB is available"""
        return self.adb_path is not None

    def check_adb_connection(self) -> bool:
        """Check if ADB daemon is running and devices are connected"""
        if not self.adb_path:
            return False

        try:
            # Start ADB server if not running
            subprocess.run(
                [self.adb_path, "start-server"], capture_output=True, timeout=10
            )

            # Get connected devices
            result = subprocess.run(
                [self.adb_path, "devices"], capture_output=True, text=True, timeout=10
            )

            if result.returncode != 0:
                logger.error(f"❌ ADB command failed: {result.stderr}")
                return False

            lines = result.stdout.strip().split("\n")[1:]  # Skip header
            devices = [line.split("\t")[0] for line in lines if "\tdevice" in line]
            self.connected_devices = devices

            if devices:
                logger.info(f"📱 Connected devices: {devices}")
                return True
            return False

        except subprocess.TimeoutExpired:
            logger.error("❌ ADB command timed out")
            return False
        except Exception as e:
            logger.error(f"❌ ADB check failed: {e}")
            return False

    def setup_reverse_tunnels(self) -> bool:
        """Setup reverse port tunnels for all connected devices"""
        if not self.adb_path or not self.connected_devices:
            return False

        success_count = 0
        for device in self.connected_devices:
            try:
                # Remove existing reverse tunnel
                subprocess.run(
                    [
                        self.adb_path,
                        "-s",
                        device,
                        "reverse",
                        "--remove",
                        f"tcp:{self.usb_port}",
                    ],
                    capture_output=True,
                    timeout=5,
                )

                # Setup new reverse tunnel
                result = subprocess.run(
                    [
                        self.adb_path,
                        "-s",
                        device,
                        "reverse",
                        f"tcp:{self.usb_port}",
                        f"tcp:{self.usb_port}",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )

                if result.returncode == 0:
                    logger.info(
                        f"✅ ADB reverse tunnel setup for device {device}: tcp:{self.usb_port}"
                    )
                    success_count += 1
                else:
                    logger.error(
                        f"❌ Failed to setup reverse tunnel for {device}: {result.stderr}"
                    )

            except subprocess.TimeoutExpired:
                logger.error(f"❌ ADB reverse command timed out for device {device}")
            except Exception as e:
                logger.error(f"❌ Error setting up reverse tunnel for {device}: {e}")

        return success_count > 0

    def cleanup_reverse_tunnels(self):
        """Remove all reverse tunnels"""
        if not self.adb_path:
            return

        for device in self.connected_devices:
            try:
                subprocess.run(
                    [self.adb_path, "-s", device, "reverse", "--remove-all"],
                    capture_output=True,
                    timeout=5,
                )
                logger.info(f"🧹 Cleaned up reverse tunnels for device {device}")
            except Exception:
                pass

    def get_device_info(self) -> Dict[str, str]:
        """Get information about connected devices"""
        if not self.adb_path:
            return {}

        device_info = {}

        for device in self.connected_devices:
            try:
                # Get device model
                result = subprocess.run(
                    [
                        self.adb_path,
                        "-s",
                        device,
                        "shell",
                        "getprop",
                        "ro.product.model",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                model = result.stdout.strip() if result.returncode == 0 else "Unknown"

                # Get Android version
                result = subprocess.run(
                    [
                        self.adb_path,
                        "-s",
                        device,
                        "shell",
                        "getprop",
                        "ro.build.version.release",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                android_version = (
                    result.stdout.strip() if result.returncode == 0 else "Unknown"
                )

                # Get device manufacturer
                result = subprocess.run(
                    [
                        self.adb_path,
                        "-s",
                        device,
                        "shell",
                        "getprop",
                        "ro.product.manufacturer",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                manufacturer = (
                    result.stdout.strip() if result.returncode == 0 else "Unknown"
                )

                device_info[
                    device
                ] = f"{manufacturer} {model} (Android {android_version})"

            except Exception as e:
                device_info[device] = f"Unknown device ({e})"

        return device_info

    def get_install_instructions(self) -> str:
        """Get platform-specific ADB installation instructions"""
        instructions = {
            "Windows": """
📱 Install ADB on Windows:
1. Download Android SDK Platform Tools from: https://developer.android.com/studio/releases/platform-tools
2. Extract to C:\\Android\\Sdk\\platform-tools\\
3. Add to PATH or install via: choco install adb""",
            "Darwin": """
📱 Install ADB on macOS:
1. Using Homebrew: brew install android-platform-tools
2. Or download from: https://developer.android.com/studio/releases/platform-tools""",
            "Linux": """
📱 Install ADB on Linux:
1. Ubuntu/Debian: sudo apt install android-tools-adb
2. CentOS/RHEL: sudo yum install android-tools
3. Arch Linux: sudo pacman -S android-tools""",
        }

        return instructions.get(
            self.system, "Install Android SDK Platform Tools for your system"
        )


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
        self.file_handle = open(self.file_path, "w+b")

        # Pre-allocate file space by writing zeros at the end
        expected_size = self.total_chunks * self.chunk_size
        if expected_size > 0:
            self.file_handle.seek(expected_size - 1)
            self.file_handle.write(b"\x00")  # Write one byte at the end
            self.file_handle.flush()

        logger.info(
            f"📦 Created and pre-allocated {expected_size:,} bytes for {self.filename}"
        )
        logger.info(f"📂 File path: {self.file_path}")

    def write_chunk(self, chunk_id: int, chunk_data: bytes):
        """Write chunk directly to disk at the correct position"""
        if self.file_handle is None:
            raise RuntimeError(
                f"File handle not initialized for transfer {self.transfer_id}"
            )

        # Calculate file position for this chunk
        file_position = chunk_id * self.chunk_size

        # Seek to correct position and write chunk
        self.file_handle.seek(file_position)
        bytes_written = self.file_handle.write(chunk_data)
        self.file_handle.flush()  # Ensure data is written to disk

        # Track that we received this chunk
        self.received_chunks.add(chunk_id)

        logger.debug(
            f"✏️ Wrote chunk {chunk_id} at position {file_position} ({bytes_written} bytes)"
        )
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
            logger.warning(
                f"🔴 Transfer {self.transfer_id} has {len(missing_chunks)} missing chunks"
            )
            logger.warning(
                f"Missing chunks: {sorted(list(missing_chunks))[:10]}{'...' if len(missing_chunks) > 10 else ''}"
            )

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
                self.file_handle.write(b"\x00" * zeros_to_write)
                logger.info(f"💾 Filled missing chunk {chunk_id} with zeros")

                # Close the file handle
                self.file_handle.close()
                self.file_handle = None

                # Get final file size
                actual_size = os.path.getsize(self.file_path)
                logger.info(f"💾 File {self.filename} finalized: {actual_size} bytes")

    def cleanup(self):
        """Clean up resources"""
        if self.file_handle is not None:
            try:
                self.file_handle.close()
            except:
                pass
            self.file_handle = None


class ProtocolHandler:
    MAGIC_HEADER = b"\x42\x54\x46\x53"
    VERSION = 1
    MAX_MESSAGE_SIZE = 2 * 1024 * 1024

    @staticmethod
    def serialize_message(msg_type: str, data: dict) -> bytes:
        json_data = json.dumps(data, separators=(",", ":")).encode("utf-8")
        msg_type_bytes = msg_type.encode("utf-8")
        header = struct.pack(
            "!4sBH",
            ProtocolHandler.MAGIC_HEADER,
            ProtocolHandler.VERSION,
            len(msg_type_bytes),
        )
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
        msg_type = data[offset : offset + msg_type_len].decode("utf-8")
        offset += msg_type_len
        data_len = struct.unpack("!I", data[offset : offset + 4])[0]
        offset += 4
        json_data = data[offset : offset + data_len]
        offset += data_len
        checksum = data[offset : offset + 32]
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

        # Initialize ADB manager for automatic USB port forwarding
        self.adb_manager = ADBManager(usb_port=usb_port)

    async def read_exact(self, reader: asyncio.StreamReader, num_bytes: int, timeout: float = 30.0) -> bytes:
        """Read the exact number of bytes with a timeout."""
        data = b""
        while len(data) < num_bytes:
            chunk = await asyncio.wait_for(reader.read(num_bytes - len(data)), timeout=timeout)
            if not chunk:
                raise asyncio.IncompleteReadError(data, num_bytes)
            data += chunk
        return data

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, channel_type: str):
        """Handle communication with the client, managing session and file transfer."""
        client_addr = writer.get_extra_info("peername")
        connection_id = f"{channel_type}_{client_addr[0]}_{client_addr[1]}_{int(time.time())}"
        logger.info(f"✅ {channel_type} client connected from {client_addr} [{connection_id}]")

        try:
            while True:
                # --- Header Parsing ---
                try:
                    header7 = await self.read_exact(reader, 7, timeout=60.0)
                    magic, version, msg_type_len = struct.unpack("!4sBH", header7)
                    if magic != ProtocolHandler.MAGIC_HEADER:
                        logger.error("Invalid magic header")
                        ack = ProtocolHandler.serialize_message(
                            "error", {"message": "Invalid magic header"}
                        )
                        writer.write(ack)
                        await writer.drain()
                        break

                    remaining_header = await self.read_exact(reader, msg_type_len + 4, timeout=10.0)
                    data_len = struct.unpack("!I", remaining_header[msg_type_len : msg_type_len + 4])[0]

                    if data_len > ProtocolHandler.MAX_MESSAGE_SIZE:
                        logger.error(f"Message too large: {data_len}")
                        ack = ProtocolHandler.serialize_message(
                            "error", {"message": "Message too large"}
                        )
                        writer.write(ack)
                        await writer.drain()
                        break

                    remaining_data = await self.read_exact(reader, data_len + 32, timeout=60.0)
                    full_message = header7 + remaining_header + remaining_data
                    msg_type, msg_data = ProtocolHandler.deserialize_message(full_message)

                except asyncio.TimeoutError as e:
                    logger.error(f"❌ {channel_type} connection timeout: {e}")
                    break
                except Exception as e:
                    logger.error(f"❌ {channel_type} error while parsing header: {e}")
                    break

                logger.info(f"✅ {channel_type} received {msg_type}")

                # --- Handle Messages ---
                if msg_type == "session_start":
                    session = TransferSession(
                        transfer_id=msg_data["transfer_id"],
                        filename=msg_data["filename"],
                        total_chunks=msg_data["total_chunks"],
                        chunk_size=msg_data["chunk_size"],
                    )

                    try:
                        session.open_file_for_writing(self.output_dir)
                        self.transfers[session.transfer_id] = session

                        ack = ProtocolHandler.serialize_message(
                            "session_ack", {"transfer_id": session.transfer_id, "status": "ready"}
                        )
                        writer.write(ack)
                        await writer.drain()
                        logger.info(f"✅ {channel_type} session started for {session.filename}")

                    except Exception as e:
                        logger.error(f"❌ Failed to initialize file for {session.filename}: {e}")
                        ack = ProtocolHandler.serialize_message(
                            "session_ack",
                            {"transfer_id": session.transfer_id, "status": "error", "message": str(e)}
                        )
                        writer.write(ack)
                        await writer.drain()

                elif msg_type == "chunk_header":
                    tid = msg_data["transfer_id"]
                    chunk_id = msg_data["chunk_id"]
                    chunk_size = msg_data["chunk_size"]
                    checksum = msg_data["checksum"]

                    try:
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
                            bytes_written = session.write_chunk(chunk_id, raw_chunk)

                            ack = ProtocolHandler.serialize_message(
                                "chunk_ack", {"transfer_id": tid, "chunk_id": chunk_id, "status": "ok", "bytes_written": bytes_written}
                            )
                            writer.write(ack)
                            await writer.drain()

                            # Log progress every 50 chunks
                            if len(session.received_chunks) % 50 == 0:
                                progress = (len(session.received_chunks) / session.total_chunks) * 100
                                logger.info(f"📈 {channel_type} progress: {len(session.received_chunks)}/{session.total_chunks} chunks ({progress:.1f}%)")
                    except Exception as e:
                        logger.error(f"❌ Error processing chunk {chunk_id}: {e}")
                        ack = ProtocolHandler.serialize_message(
                            "chunk_ack",
                            {"transfer_id": tid, "chunk_id": chunk_id, "status": "error", "message": str(e)}
                        )
                        writer.write(ack)
                        await writer.drain()

                elif msg_type == "transfer_complete":
                    tid = msg_data["transfer_id"]
                    if tid in self.transfers:
                        session = self.transfers[tid]

                        try:
                            # Finalize the file (handle missing chunks, close file handle)
                            session.finalize_file()

                            # Log the transfer details
                            duration = int((time.time() - session.start_time) * 1000)
                            file_size = os.path.getsize(session.file_path)
                            chunks_received = len(session.received_chunks)

                            logger.info(f"✅ Transfer {tid} complete!")
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

                            logger.info(f"🧠 Memory saved: {memory_saved:,} bytes ({savings_percent:.1f}% reduction)")

                        except Exception as e:
                            logger.error(f"❌ Error finalizing transfer {tid}: {e}")

                        finally:
                            # Clean up transfer session
                            session.cleanup()
                            del self.transfers[tid]

                    # Send acknowledgment
                    ack = ProtocolHandler.serialize_message(
                        "transfer_ack", {"transfer_id": tid, "status": "done"}
                    )
                    writer.write(ack)
                    await writer.drain()

        except asyncio.IncompleteReadError as e:
            logger.warning(f"{channel_type} connection closed by client")
        except Exception as e:
            logger.error(f"{channel_type} error: {e}")
            import traceback
            logger.error(traceback.format_exc())
        finally:
            # Clean up incomplete transfers
            for tid, session in list(self.transfers.items()):
                if session.file_handle is not None:
                    logger.info(f"🧹 Cleaning up incomplete transfer {tid}")
                    session.cleanup()
                    del self.transfers[tid]

            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
            logger.info(f"🔌 {channel_type} [{connection_id}] connection closed")



    async def _setup_adb_tunnels(self):
        """Setup ADB tunnels for USB connections"""
        if not self.adb_manager.is_available():
            logger.info("📱 ADB not found - USB connections require manual port forwarding")
            logger.info("💡 Install Android SDK platform-tools for automatic USB setup")
            logger.info(self.adb_manager.get_install_instructions())
            return

        logger.info("🔌 Setting up ADB USB tunnels...")

        if self.adb_manager.check_adb_connection():
            if self.adb_manager.setup_reverse_tunnels():
                device_info = self.adb_manager.get_device_info()
                for device_id, info in device_info.items():
                    logger.info(f"📱 {device_id}: {info}")
                logger.info("✅ ADB USB tunnels ready")
            else:
                logger.warning("⚠️ Failed to setup ADB tunnels")
        else:
            logger.info(
                "💡 Connect Android device via USB and enable USB debugging for USB transfers"
            )


    async def start_server(self):
            """Starts the dual-channel server, handles USB and WiFi connections."""
            logger.info("🚀 Starting Dual-Channel Server ...")
            logger.info("📝 This version writes chunks directly to disk for 90%+ memory reduction")
            logger.info(f"🖥️ Platform: {platform.system()} {platform.release()}")

            # Setup ADB tunnels for USB connections
            await self._setup_adb_tunnels()

            try:
                usb_server = await asyncio.start_server(
                    lambda r, w: self.handle_client(r, w, "USB"), "127.0.0.1", self.usb_port
                )
                wifi_server = await asyncio.start_server(
                    lambda r, w: self.handle_client(r, w, "WiFi"), "0.0.0.0", self.wifi_port
                )

                logger.info(f"🌍 USB Server: 127.0.0.1:{self.usb_port}")
                logger.info(f"📶 WiFi Server: 0.0.0.0:{self.wifi_port}")
                logger.info(f"🧹 Output directory: {os.path.abspath(self.output_dir)}")
                logger.info("🚀 Server ready for connections")

                async with usb_server, wifi_server:
                    await asyncio.gather(usb_server.serve_forever(), wifi_server.serve_forever())

            except Exception as e:
                logger.error(f"❌ Error starting server: {e}")
                import traceback
                logger.error(traceback.format_exc())
            finally:
                logger.info("🧹 Cleaning up ADB tunnels...")
                self.adb_manager.cleanup_reverse_tunnels()



async def main():
    """Main function to initialize the server and handle lifecycle."""
    server = DualChannelServer()
    try:
        await server.start_server()
    except KeyboardInterrupt:
        logger.info("🛑 Server interrupted by user")
    except Exception as e:
        logger.error(f"❌ Server error: {e}")
        import traceback
        logger.error(traceback.format_exc())
    finally:
        logger.info("🧹 Server shutdown complete")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("🛑 Server stopped by user")
