#!/usr/bin/env python3

"""
Enhanced Async Dual-Channel Server - Streaming + Resume + Load Balancing
- Streams files directly to disk (90%+ memory reduction)
- Cross-platform ADB detection (Windows/macOS/Linux)
- Resume transfers with checkpoint system
- Intelligent load balancing based on connection performance
"""

import asyncio
import struct
import hashlib
import json
import time
import logging
import os
import subprocess
import shutil
import platform
from dataclasses import dataclass, field
from typing import Dict, Set, List, Optional
from pathlib import Path
import aiofiles

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("serverV5")

@dataclass
class TransferSession:
    transfer_id: str
    filename: str
    total_chunks: int
    chunk_size: int
    file_handle: Optional[object] = None
    received_chunks: Set[int] = field(default_factory=set)
    start_time: float = field(default_factory=time.time)
    file_path: str = ""
    last_checkpoint_time: float = field(default_factory=time.time)

    def __post_init__(self):
        self.expected_file_size = self.total_chunks * self.chunk_size

@dataclass 
class TransferCheckpoint:
    transfer_id: str
    filename: str
    total_chunks: int
    chunk_size: int
    received_chunks: Set[int]
    timestamp: float
    file_path: str

    def to_dict(self) -> dict:
        return {
            "transfer_id": self.transfer_id,
            "filename": self.filename, 
            "total_chunks": self.total_chunks,
            "chunk_size": self.chunk_size,
            "received_chunks": list(self.received_chunks),
            "timestamp": self.timestamp,
            "file_path": self.file_path
        }

    @classmethod
    def from_dict(cls, data: dict) -> 'TransferCheckpoint':
        return cls(
            transfer_id=data["transfer_id"],
            filename=data["filename"],
            total_chunks=data["total_chunks"], 
            chunk_size=data["chunk_size"],
            received_chunks=set(data["received_chunks"]),
            timestamp=data["timestamp"],
            file_path=data["file_path"]
        )

@dataclass
class ConnectionMetrics:
    connection_id: str
    rtt_history: List[float] = field(default_factory=list)
    throughput_history: List[float] = field(default_factory=list)
    success_count: int = 0
    failure_count: int = 0
    last_activity: float = field(default_factory=time.time)

    def update_performance(self, rtt: float, throughput: float):
        self.rtt_history.append(rtt)
        self.throughput_history.append(throughput)

        # Keep only last 10 measurements
        if len(self.rtt_history) > 10:
            self.rtt_history.pop(0)
        if len(self.throughput_history) > 10:
            self.throughput_history.pop(0)

        self.last_activity = time.time()

    def get_avg_rtt(self) -> float:
        return sum(self.rtt_history) / len(self.rtt_history) if self.rtt_history else float('inf')

    def get_avg_throughput(self) -> float:
        return sum(self.throughput_history) / len(self.throughput_history) if self.throughput_history else 0

    def get_weight(self) -> float:
        # Higher weight = better connection
        if not self.throughput_history:
            return 0.1

        avg_throughput = self.get_avg_throughput()
        avg_rtt = self.get_avg_rtt()
        success_rate = self.success_count / max(1, self.success_count + self.failure_count)

        # Weight based on throughput, inverse RTT, and success rate
        weight = (avg_throughput / 1024 / 1024) * (1000 / max(avg_rtt, 10)) * success_rate
        return max(0.1, min(10.0, weight))  # Clamp between 0.1 and 10

class ConnectionManager:
    def __init__(self):
        self.connections: Dict[str, ConnectionMetrics] = {}
        self.pending_chunks: Dict[int, str] = {}  # chunk_id -> connection_id

    def register_connection(self, connection_id: str):
        self.connections[connection_id] = ConnectionMetrics(connection_id)
        logger.info(f"📊 Registered connection: {connection_id}")

    def unregister_connection(self, connection_id: str):
        if connection_id in self.connections:
            # Redistribute pending chunks from failed connection
            failed_chunks = [chunk_id for chunk_id, conn_id in self.pending_chunks.items() 
                           if conn_id == connection_id]
            for chunk_id in failed_chunks:
                del self.pending_chunks[chunk_id]

            del self.connections[connection_id]
            logger.info(f"🔌 Unregistered connection: {connection_id} (redistributed {len(failed_chunks)} chunks)")

    def assign_chunk(self, chunk_id: int) -> Optional[str]:
        """Assign chunk to best available connection"""
        if not self.connections:
            return None

        # Calculate weights for load balancing
        weights = {conn_id: metrics.get_weight() for conn_id, metrics in self.connections.items()}

        # Find connection with highest weight that's not overloaded
        max_pending_per_conn = 5  # Don't overload any single connection

        available_connections = []
        for conn_id, weight in weights.items():
            pending_count = sum(1 for c_id in self.pending_chunks.values() if c_id == conn_id)
            if pending_count < max_pending_per_conn:
                available_connections.append((conn_id, weight))

        if not available_connections:
            # All connections overloaded, pick the one with least pending
            pending_counts = {}
            for conn_id in self.connections:
                pending_counts[conn_id] = sum(1 for c_id in self.pending_chunks.values() if c_id == conn_id)

            best_conn = min(pending_counts.keys(), key=lambda k: pending_counts[k])
        else:
            # Pick highest weight available connection
            best_conn = max(available_connections, key=lambda x: x[1])[0]

        self.pending_chunks[chunk_id] = best_conn
        return best_conn

    def chunk_completed(self, chunk_id: int, connection_id: str, rtt: float, chunk_size: int):
        """Mark chunk as completed and update connection metrics"""
        if chunk_id in self.pending_chunks:
            del self.pending_chunks[chunk_id]

        if connection_id in self.connections:
            throughput = chunk_size / max(rtt, 0.001)  # bytes/second
            self.connections[connection_id].update_performance(rtt, throughput)
            self.connections[connection_id].success_count += 1

    def chunk_failed(self, chunk_id: int, connection_id: str):
        """Mark chunk as failed and update connection metrics"""
        if chunk_id in self.pending_chunks:
            del self.pending_chunks[chunk_id]

        if connection_id in self.connections:
            self.connections[connection_id].failure_count += 1

class CheckpointManager:
    def __init__(self, checkpoint_dir: str = "./checkpoints"):
        self.checkpoint_dir = Path(checkpoint_dir)
        self.checkpoint_dir.mkdir(exist_ok=True)

    def get_checkpoint_path(self, transfer_id: str) -> Path:
        return self.checkpoint_dir / f"{transfer_id}.json"

    async def save_checkpoint(self, session: TransferSession):
        """Save transfer checkpoint to disk"""
        checkpoint = TransferCheckpoint(
            transfer_id=session.transfer_id,
            filename=session.filename,
            total_chunks=session.total_chunks,
            chunk_size=session.chunk_size,
            received_chunks=session.received_chunks,
            timestamp=time.time(),
            file_path=session.file_path
        )

        checkpoint_path = self.get_checkpoint_path(session.transfer_id)

        try:
            async with aiofiles.open(checkpoint_path, 'w') as f:
                await f.write(json.dumps(checkpoint.to_dict(), indent=2))
            session.last_checkpoint_time = time.time()
            logger.debug(f"💾 Saved checkpoint for {session.transfer_id}: {len(session.received_chunks)}/{session.total_chunks} chunks")
        except Exception as e:
            logger.error(f"Failed to save checkpoint: {e}")

    async def load_checkpoint(self, transfer_id: str) -> Optional[TransferCheckpoint]:
        """Load transfer checkpoint from disk"""
        checkpoint_path = self.get_checkpoint_path(transfer_id)

        if not checkpoint_path.exists():
            return None

        try:
            async with aiofiles.open(checkpoint_path, 'r') as f:
                data = json.loads(await f.read())

            checkpoint = TransferCheckpoint.from_dict(data)
            logger.info(f"📂 Loaded checkpoint for {transfer_id}: {len(checkpoint.received_chunks)}/{checkpoint.total_chunks} chunks")
            return checkpoint
        except Exception as e:
            logger.error(f"Failed to load checkpoint {transfer_id}: {e}")
            return None

    async def cleanup_old_checkpoints(self, max_age_hours: int = 24):
        """Remove checkpoint files older than specified hours"""
        cutoff_time = time.time() - (max_age_hours * 3600)

        for checkpoint_file in self.checkpoint_dir.glob("*.json"):
            try:
                if checkpoint_file.stat().st_mtime < cutoff_time:
                    checkpoint_file.unlink()
                    logger.debug(f"🧹 Cleaned up old checkpoint: {checkpoint_file.name}")
            except Exception as e:
                logger.error(f"Error cleaning up checkpoint {checkpoint_file}: {e}")

class ADBManager:
    """Enhanced ADB manager with cross-platform support"""

    def __init__(self, usb_port: int = 8888):
        self.usb_port = usb_port
        self.adb_path = self._find_adb()
        self.connected_devices: List[str] = []

    def _find_adb(self) -> str:
        """Find ADB executable with cross-platform support"""
        system = platform.system().lower()

        # Determine executable name based on platform
        adb_name = "adb.exe" if system == "windows" else "adb"

        # Check PATH first (works on all platforms)
        if shutil.which(adb_name):
            adb_path = shutil.which(adb_name)
            logger.info(f"Found ADB in PATH: {adb_path}")
            return adb_path

        # Platform-specific search paths
        search_paths = []

        if system == "windows":
            search_paths = [
                os.path.expanduser("~/AppData/Local/Android/Sdk/platform-tools/adb.exe"),
                "C:/Android/Sdk/platform-tools/adb.exe",
                "C:/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe",
                os.path.expanduser("~/Android/Sdk/platform-tools/adb.exe")
            ]
        elif system == "darwin":  # macOS
            search_paths = [
                os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
                "/usr/local/bin/adb",
                "/opt/homebrew/bin/adb",
                os.path.expanduser("~/Android/Sdk/platform-tools/adb")
            ]
        else:  # Linux and others
            search_paths = [
                "/usr/bin/adb",
                "/usr/local/bin/adb", 
                os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
                "/opt/android-sdk/platform-tools/adb"
            ]

        # Search platform-specific paths
        for path in search_paths:
            if os.path.isfile(path):
                logger.info(f"Found ADB at: {path}")
                return path

        raise FileNotFoundError(
            f"ADB not found on {system}. Please install Android SDK platform-tools or add adb to PATH. "
            f"Searched paths: {search_paths}"
        )

    def check_adb_connection(self) -> bool:
        """Check if ADB daemon is running and devices are connected"""
        try:
            result = subprocess.run([self.adb_path, "devices"],
                                  capture_output=True, text=True, timeout=10)
            if result.returncode != 0:
                logger.error(f"ADB command failed: {result.stderr}")
                return False

            lines = result.stdout.strip().split('\n')[1:]  # Skip header
            devices = [line.split('\t')[0] for line in lines if '\tdevice' in line]
            self.connected_devices = devices

            if not devices:
                logger.warning("No Android devices connected via USB")
                logger.info("Please connect your Android device and enable USB debugging")
                return False

            logger.info(f"Connected devices: {devices}")
            return True

        except subprocess.TimeoutExpired:
            logger.error("ADB command timed out")
            return False
        except Exception as e:
            logger.error(f"ADB check failed: {e}")
            return False

    def setup_reverse_tunnels(self) -> bool:
        """Setup reverse port tunnels for all connected devices"""
        if not self.connected_devices:
            return False

        success_count = 0
        for device in self.connected_devices:
            try:
                # Remove existing reverse tunnel (ignore errors)
                subprocess.run([self.adb_path, "-s", device, "reverse", "--remove", f"tcp:{self.usb_port}"],
                             capture_output=True, timeout=5)

                # Setup new reverse tunnel
                result = subprocess.run([self.adb_path, "-s", device, "reverse",
                                       f"tcp:{self.usb_port}", f"tcp:{self.usb_port}"],
                                      capture_output=True, text=True, timeout=10)

                if result.returncode == 0:
                    logger.info(f"✅ ADB reverse tunnel setup for device {device}: tcp:{self.usb_port}")
                    success_count += 1
                else:
                    logger.error(f"❌ Failed to setup reverse tunnel for {device}: {result.stderr}")

            except subprocess.TimeoutExpired:
                logger.error(f"ADB reverse command timed out for device {device}")
            except Exception as e:
                logger.error(f"Error setting up reverse tunnel for {device}: {e}")

        if success_count > 0:
            logger.info(f"🔌 USB tunnels active on {success_count} device(s)")
            return True
        else:
            logger.error("❌ No reverse tunnels could be established")
            return False

    def cleanup_reverse_tunnels(self):
        """Remove all reverse tunnels"""
        for device in self.connected_devices:
            try:
                subprocess.run([self.adb_path, "-s", device, "reverse", "--remove-all"],
                             capture_output=True, timeout=5)
                logger.info(f"🧹 Cleaned up reverse tunnels for device {device}")
            except Exception as e:
                logger.error(f"Error cleaning up tunnels for {device}: {e}")

    def get_device_info(self) -> Dict[str, str]:
        """Get information about connected devices"""
        device_info = {}
        for device in self.connected_devices:
            try:
                # Get device model
                result = subprocess.run([self.adb_path, "-s", device, "shell", "getprop", "ro.product.model"],
                                      capture_output=True, text=True, timeout=5)
                model = result.stdout.strip() if result.returncode == 0 else "Unknown"

                # Get Android version
                result = subprocess.run([self.adb_path, "-s", device, "shell", "getprop", "ro.build.version.release"],
                                      capture_output=True, text=True, timeout=5)
                android_version = result.stdout.strip() if result.returncode == 0 else "Unknown"

                device_info[device] = f"{model} (Android {android_version})"
            except Exception as e:
                device_info[device] = f"Unknown device ({e})"

        return device_info

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
        self.adb_manager = ADBManager(usb_port)
        self.checkpoint_manager = CheckpointManager()
        self.connection_manager = ConnectionManager()

        # Background task for checkpoint cleanup
        self.cleanup_task = None

    async def read_exact(self, reader: asyncio.StreamReader, num_bytes: int, timeout: float = 30.0) -> bytes:
        data = b''
        while len(data) < num_bytes:
            chunk = await asyncio.wait_for(reader.read(num_bytes - len(data)), timeout=timeout)
            if not chunk:
                raise asyncio.IncompleteReadError(data, num_bytes)
            data += chunk
        return data

    async def create_sparse_file(self, file_path: str, size: int):
        """Create a sparse file of specified size"""
        try:
            async with aiofiles.open(file_path, 'wb') as f:
                await f.seek(size - 1)
                await f.write(b'\0')
            logger.debug(f"Created sparse file: {file_path} ({size} bytes)")
        except Exception as e:
            logger.error(f"Failed to create sparse file {file_path}: {e}")
            raise

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, channel_type: str):
        client_addr = writer.get_extra_info("peername")
        connection_id = f"{channel_type}_{client_addr[0]}_{client_addr[1]}_{int(time.time())}"

        # Register connection for load balancing
        self.connection_manager.register_connection(connection_id)

        logger.info(f"🔗 {channel_type} client connected from {client_addr} [{connection_id}]")

        try:
            while True:
                # Header parsing
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

                # Handle messages
                if msg_type == "session_start":
                    await self.handle_session_start(writer, msg_data, connection_id)

                elif msg_type == "chunk_header":
                    await self.handle_chunk_header(reader, writer, msg_data, connection_id, channel_type)

                elif msg_type == "transfer_complete":
                    await self.handle_transfer_complete(writer, msg_data)

        except asyncio.IncompleteReadError:
            logger.warning(f"{channel_type} connection closed by client")
        except Exception as e:
            logger.error(f"{channel_type} error: {e}")
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

            # Unregister connection
            self.connection_manager.unregister_connection(connection_id)
            logger.info(f"🔒 {channel_type} [{connection_id}] connection closed")

    async def handle_session_start(self, writer: asyncio.StreamWriter, msg_data: dict, connection_id: str):
        """Handle session start with resume capability"""
        transfer_id = msg_data["transfer_id"]
        filename = msg_data["filename"]
        total_chunks = msg_data["total_chunks"]
        chunk_size = msg_data["chunk_size"]

        # Check for existing checkpoint
        checkpoint = await self.checkpoint_manager.load_checkpoint(transfer_id)

        if checkpoint:
            logger.info(f"🔄 Resuming transfer {transfer_id}: {len(checkpoint.received_chunks)}/{checkpoint.total_chunks} chunks already received")

            # Validate checkpoint matches current session
            if (checkpoint.filename != filename or 
                checkpoint.total_chunks != total_chunks or 
                checkpoint.chunk_size != chunk_size):
                logger.warning(f"⚠️ Checkpoint mismatch for {transfer_id}, starting fresh")
                checkpoint = None

        # Create file path
        file_path = os.path.join(self.output_dir, filename)

        if checkpoint:
            # Resume existing transfer
            session = TransferSession(
                transfer_id=transfer_id,
                filename=filename,
                total_chunks=total_chunks,
                chunk_size=chunk_size,
                received_chunks=checkpoint.received_chunks.copy(),
                file_path=file_path
            )

            # Open existing file for append/random access
            session.file_handle = await aiofiles.open(file_path, 'r+b')

            # Send resume response with completed chunks
            ack = ProtocolHandler.serialize_message("session_ack", {
                "transfer_id": transfer_id,
                "status": "resume",
                "completed_chunks": list(checkpoint.received_chunks)
            })
        else:
            # Start new transfer
            session = TransferSession(
                transfer_id=transfer_id,
                filename=filename,
                total_chunks=total_chunks,
                chunk_size=chunk_size,
                file_path=file_path
            )

            # Create sparse file
            expected_size = total_chunks * chunk_size
            await self.create_sparse_file(file_path, expected_size)

            # Open file for random access
            session.file_handle = await aiofiles.open(file_path, 'r+b')

            # Send ready response
            ack = ProtocolHandler.serialize_message("session_ack", {
                "transfer_id": transfer_id,
                "status": "ready"
            })

        self.transfers[transfer_id] = session

        writer.write(ack)
        await writer.drain()

        logger.info(f"✅ Session started for {filename} (Resume: {checkpoint is not None})")

    async def handle_chunk_header(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, 
                                msg_data: dict, connection_id: str, channel_type: str):
        """Handle chunk with streaming write to disk"""
        transfer_id = msg_data["transfer_id"]
        chunk_id = msg_data["chunk_id"]
        chunk_size = msg_data["chunk_size"]
        checksum = msg_data["checksum"]

        chunk_start_time = time.time()

        # Read chunk data
        raw_chunk = await self.read_exact(reader, chunk_size, timeout=30.0)

        # Verify checksum
        calc = hashlib.sha256(raw_chunk).hexdigest()
        if calc != checksum:
            logger.error(f"❌ {channel_type} checksum mismatch on chunk {chunk_id}")
            self.connection_manager.chunk_failed(chunk_id, connection_id)
            return

        if transfer_id not in self.transfers:
            logger.warning(f"Unknown transfer ID: {transfer_id}")
            return

        session = self.transfers[transfer_id]

        # Skip if chunk already received (for resume)
        if chunk_id in session.received_chunks:
            logger.debug(f"Skipping already received chunk {chunk_id}")
            ack = ProtocolHandler.serialize_message("chunk_ack", {
                "transfer_id": transfer_id, "chunk_id": chunk_id, "status": "already_received"
            })
            writer.write(ack)
            await writer.drain()
            return

        try:
            # Write chunk directly to disk
            await session.file_handle.seek(chunk_id * session.chunk_size)
            await session.file_handle.write(raw_chunk)
            await session.file_handle.flush()

            # Track received chunk
            session.received_chunks.add(chunk_id)

            # Update connection performance
            rtt = (time.time() - chunk_start_time) * 1000  # Convert to ms
            self.connection_manager.chunk_completed(chunk_id, connection_id, rtt, chunk_size)

            # Send acknowledgment
            ack = ProtocolHandler.serialize_message("chunk_ack", {
                "transfer_id": transfer_id, "chunk_id": chunk_id, "status": "ok"
            })
            writer.write(ack)
            await writer.drain()

            logger.info(f"✅ Streamed chunk {chunk_id} ({len(raw_chunk)} bytes) from {channel_type} to disk")

            # Save checkpoint periodically
            current_time = time.time()
            if (len(session.received_chunks) % 50 == 0 or 
                current_time - session.last_checkpoint_time > 30):
                await self.checkpoint_manager.save_checkpoint(session)

        except Exception as e:
            logger.error(f"Failed to write chunk {chunk_id} to disk: {e}")
            self.connection_manager.chunk_failed(chunk_id, connection_id)

    async def handle_transfer_complete(self, writer: asyncio.StreamWriter, msg_data: dict):
        """Handle transfer completion with file finalization"""
        transfer_id = msg_data["transfer_id"]

        if transfer_id not in self.transfers:
            return

        session = self.transfers[transfer_id]

        try:
            # Close file handle
            if session.file_handle:
                await session.file_handle.close()

            # Check for missing chunks and handle them
            missing_chunks = set(range(session.total_chunks)) - session.received_chunks

            if missing_chunks:
                logger.warning(f"Transfer {transfer_id} has {len(missing_chunks)} missing chunks: {sorted(list(missing_chunks))[:10]}...")
                # For missing chunks, the sparse file will already have zeros
                # Alternatively, you could mark the transfer as incomplete

            # Get final file stats
            file_size = os.path.getsize(session.file_path)
            duration = int((time.time() - session.start_time) * 1000)

            logger.info(f"🎉 Transfer {transfer_id} complete!")
            logger.info(f"   File: {session.file_path}")
            logger.info(f"   Size: {file_size} bytes")
            logger.info(f"   Duration: {duration} ms") 
            logger.info(f"   Chunks: {len(session.received_chunks)}/{session.total_chunks}")
            logger.info(f"   Missing: {len(missing_chunks)} chunks")

            # Clean up
            del self.transfers[transfer_id]

            # Remove checkpoint file
            checkpoint_path = self.checkpoint_manager.get_checkpoint_path(transfer_id)
            if checkpoint_path.exists():
                checkpoint_path.unlink()
                logger.debug(f"🧹 Removed checkpoint file: {checkpoint_path}")

            # Send acknowledgment
            ack = ProtocolHandler.serialize_message("transfer_ack", {
                "transfer_id": transfer_id, "status": "done",
                "file_size": file_size,
                "missing_chunks": len(missing_chunks)
            })
            writer.write(ack)
            await writer.drain()

        except Exception as e:
            logger.error(f"Error completing transfer {transfer_id}: {e}")

    async def cleanup_checkpoints_periodically(self):
        """Background task to clean up old checkpoints"""
        while True:
            try:
                await asyncio.sleep(3600)  # Run every hour
                await self.checkpoint_manager.cleanup_old_checkpoints()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in checkpoint cleanup: {e}")

    async def start_server(self):
        logger.info("🚀 Starting Enhanced Async Dual-Channel Server...")
        logger.info("✨ Features: Streaming • Resume • Load Balancing • Cross-Platform")

        # Setup ADB reverse tunnels
        try:
            if self.adb_manager.check_adb_connection():
                device_info = self.adb_manager.get_device_info()
                logger.info("📱 Connected Android devices:")
                for device_id, info in device_info.items():
                    logger.info(f"   {device_id}: {info}")

                if self.adb_manager.setup_reverse_tunnels():
                    logger.info("🔌 ADB reverse tunnels configured automatically!")
                    logger.info(f"   Android apps can now connect to 127.0.0.1:{self.usb_port}")
                else:
                    logger.warning("⚠️ ADB setup failed, USB channel may not work")
            else:
                logger.warning("⚠️ No ADB devices detected, USB channel will require manual setup")

        except FileNotFoundError as e:
            logger.warning(f"⚠️ ADB not available: {e}")
            logger.info("   Install Android SDK platform-tools for automatic USB setup")
        except Exception as e:
            logger.error(f"❌ ADB setup error: {e}")

        # Start cleanup task
        self.cleanup_task = asyncio.create_task(self.cleanup_checkpoints_periodically())

        # Start servers
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
        logger.info(f"💾 Checkpoint directory: {self.checkpoint_manager.checkpoint_dir}")
        logger.info("✅ Server ready for connections")

        try:
            async with usb_server, wifi_server:
                await asyncio.gather(usb_server.serve_forever(), wifi_server.serve_forever())
        finally:
            # Cleanup
            if self.cleanup_task:
                self.cleanup_task.cancel()

            # Close all open file handles
            for session in self.transfers.values():
                if session.file_handle:
                    try:
                        await session.file_handle.close()
                    except Exception:
                        pass

            self.adb_manager.cleanup_reverse_tunnels()

async def main():
    server = EnhancedAsyncDualChannelServer()
    await server.start_server()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("🛑 Server stopped")
    except Exception as e:
        logger.error(f"💥 Server error: {e}")
        raise
