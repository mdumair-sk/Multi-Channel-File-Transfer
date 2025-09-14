# Multi-Channel File Transfer

## Overview

Multi-Channel File Transfer is a high-performance, multi-threaded system that allows for fast and efficient file transfer across different network channels. It is designed to make file transfer more reliable, leveraging multiple channels to maximize throughput and reduce transfer time. This tool supports file splitting, parallel transfers, and recovery from network failures.

## Features

* **Multi-channel transfer**: Transfer files over multiple channels simultaneously.
* **Resilience**: Recover from connection drops and resume file transfers without data loss.
* **File Splitting**: Large files are split into smaller chunks to optimize transfer performance.
* **Parallelism**: Supports multiple simultaneous connections for faster file transfers.
* **Encryption**: Transfers can be encrypted to ensure data security during transit.
* **Cross-platform**: Works on Linux, macOS, and Windows systems.

## Table of Contents

* [Features](#features)
* [Installation](#installation)
* [Prerequisites](#prerequisites)
* [Usage](#usage)
* [Commands](#commands)
* [Configuration](#configuration)
* [Contributing](#contributing)
* [License](#license)

## Installation

### Clone the repository

```bash
git clone https://github.com/mdumair-sk/Multi-Channel-File-Transfer.git
cd Multi-Channel-File-Transfer
```

### Install dependencies

The project requires Python 3.x and some additional Python libraries. You can install the necessary dependencies using `pip`.

```bash
pip install -r requirements.txt
```

## Prerequisites

* **Python 3.x**: This project requires Python version 3.x or higher.
* **Network access**: Since this is a file transfer tool, ensure that your network configuration allows open connections on the required ports.
* **Dependencies**: The `requirements.txt` file includes the necessary dependencies (e.g., threading, socket libraries, cryptography, etc.).

## Usage

### Start a file transfer

To initiate a file transfer, use the following command:

```bash
python transfer.py --source <source_file_path> --destination <destination_file_path> --host <host> --port <port>
```

#### Parameters:

* `--source`: Path to the source file you want to transfer.
* `--destination`: Path where the file will be saved on the receiving machine.
* `--host`: Host address of the receiving machine.
* `--port`: Port number on which the receiving machine is listening for the transfer.

### Multi-channel transfer mode

To enable multi-channel transfer, use the `--channels` option to specify the number of parallel channels to use.

```bash
python transfer.py --source <source_file_path> --destination <destination_file_path> --host <host> --port <port> --channels <number_of_channels>
```

Where:

* `<number_of_channels>` is the number of channels to use for the file transfer (e.g., 4).

### Encryption

To enable encryption during file transfer, use the `--encrypt` flag:

```bash
python transfer.py --source <source_file_path> --destination <destination_file_path> --host <host> --port <port> --encrypt
```

This will encrypt the file during transmission to ensure data security.

### Resuming an interrupted transfer

If a transfer is interrupted, it can be resumed by using the `--resume` flag. This will continue the transfer from where it left off:

```bash
python transfer.py --source <source_file_path> --destination <destination_file_path> --host <host> --port <port> --resume
```

## Commands

### Server Command (Receiver)

On the receiving end, start the server to listen for incoming file transfers:

```bash
python server.py --port <port>
```

This will start a server on the specified port. The server will listen for incoming file transfer requests from clients.

### Client Command (Sender)

On the sending end, start the client to initiate the file transfer:

```bash
python client.py --source <source_file_path> --destination <destination_file_path> --host <host> --port <port> --channels <number_of_channels>
```

The client will send the file to the server using the specified host, port, and number of channels.

## Configuration

The following configuration options can be set in the `config.json` file:

```json
{
    "default_host": "localhost",
    "default_port": 8080,
    "default_channels": 4,
    "default_encryption": false
}
```

You can modify this file to set default values for the host, port, number of channels, and whether encryption is enabled.

## Example Workflow

1. **On the receiver machine**, start the server:

   ```bash
   python server.py --port 8080
   ```

2. **On the sender machine**, start the client with the file to be transferred:

   ```bash
   python client.py --source /path/to/source/file --destination /path/to/destination/file --host <receiver_host> --port 8080 --channels 4
   ```

3. The transfer will start, and the file will be transferred over multiple channels for optimal speed.

## Contributing

We welcome contributions to this project! If you’d like to contribute, follow these steps:

1. Fork the repository.
2. Create a new branch (`git checkout -b feature-branch`).
3. Make your changes and commit them (`git commit -am 'Add new feature'`).
4. Push to your forked repository (`git push origin feature-branch`).
5. Open a pull request to the main repository.

Please ensure that your changes follow the code style guidelines and pass the tests before submitting a pull request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
