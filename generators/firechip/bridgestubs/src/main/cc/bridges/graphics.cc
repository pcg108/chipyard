// See LICENSE for license details

#include "graphics.h"
#include "core/simif.h"

#include <fcntl.h>
#include <sys/stat.h>

#ifndef _XOPEN_SOURCE
#define _XOPEN_SOURCE
#endif

#include <stdio.h>
#include <stdlib.h>
#include <iostream>
#include <inttypes.h>
#include <bit>
#include <cstdint>

#ifndef _WIN32
#include <unistd.h>

char graphics_t::KIND;

#endif

uint32_t reverseBytes(uint32_t value) {
  return ((value >> 24) & 0x000000FF) | // Move byte 3 to byte 0
         ((value >> 8)  & 0x0000FF00) | // Move byte 2 to byte 1
         ((value << 8)  & 0x00FF0000) | // Move byte 1 to byte 2
         ((value << 24) & 0xFF000000);  // Move byte 0 to byte 3
}


std::optional<uint32_t> graphics_handler::get() {

  /*
    graphics_handler::get() deals with reading from the socket connection for new stream messages and translating them into a series of 4-byte packets that can be sent over MMIO
    It takes multiple calls to get() to completely transmit a stream message
  */

  // if we are not currently transmitting a previous stream message, we can check if there is a new stream message to send
  if (stream_packet_send_total == 0) {

    ssize_t bytes_received_from_host = ::recv(sockfd, txbuffer, BUFFER_SIZE - 1, 0);
  
    if (bytes_received_from_host > 0) {

      // host2middle sent the connection_id as the first byte
      uint8_t connection_id = txbuffer[0];

      // construct the start-stream packet for the target as {16'b1, connection_id, size}
      uint32_t start_stream = static_cast<uint32_t>(0xFFFF);
      uint32_t id           = static_cast<uint32_t>(connection_id) & 0xFF;  
      uint32_t size         = static_cast<uint32_t>(bytes_received_from_host - 1) & 0xFF;

      // std::cout << "[bridge driver] Received " << size << " bytes from host connection " << id << std::endl;
      // std::cout << "start-stream: " << start_stream << std::endl;
      // std::cout << "connection_id: " << connection_id << std::endl;
      // std::cout << "size: " << size << std::endl;

      start_stream = (start_stream << 16) | (id << 8) | size;

      // std::cout << "created start packet: " << start_stream << std::endl;

      int index = 0;
      stream_packets[index++] = start_stream;

      // construct the packets for the bridge (break up the bytes into sets of 4-byte messages encoded as uint32_t)
      for (int i = 0; i < (bytes_received_from_host-1) / 4; i++) {
        uint32_t b0 = txbuffer[4*i + 0 + 1] & 0xFF;
        uint32_t b1 = txbuffer[4*i + 1 + 1] & 0xFF;
        uint32_t b2 = txbuffer[4*i + 2 + 1] & 0xFF;
        uint32_t b3 = txbuffer[4*i + 3 + 1] & 0xFF;

        uint32_t packet = (b3 << 24) | (b2 << 16) | (b1 << 8) | (b0);

        stream_packets[index++] = packet;
        // std::cout << "created packet: " << packet << std::endl;
      }

      // construct the last packet if total size isn't a multiple of 4
      if (4*(index-1) < bytes_received_from_host - 1) {

        uint32_t last_packet = 0;
        for (int i = bytes_received_from_host-1; i >= 4*(index-1)+1; i--) {
          uint32_t byte = static_cast<uint32_t>(txbuffer[i]) & 0xFF;  
          last_packet = (last_packet << 8) | byte;
        }
        stream_packets[index++] = last_packet;
        // std::cout << "created last packet: " << last_packet << std::endl;

      } 

      stream_packet_send_total = index;
      stream_packet_send_index = 0;

      // if header type_ was KUMQUAT_GPU_PROTOCOL_RESP_CONNECTION_ID, then store the rutabaga ID corresponding to this stream connection_id
      if (stream_packets[1] == 0x3010) {
        int rutabaga_id = stream_packets[2];
        if (conn_to_rutabaga_id.find(rutabaga_id) == conn_to_rutabaga_id.end()) {
          conn_to_rutabaga_id[connection_id] = rutabaga_id;
        }
      }

      /*
      // check if the stream message is KUMQUAT_GPU_PROTOCOL_RESP_HOST_COPY_BUFFER and the header payload top 16 bits are 0
      // this indicates that the host copied into the copy-buffer and we need to transfer the copy-buffer to the target
      if (stream_packets[1] == 0x3009) {    // control header _type is first u32
        
        uint32_t payload = stream_packets[2]; // reverseBytes(stream_packets[2]);

        // std::cout << "[bridge driver] host response to copy buffer: " << payload << std::endl;
        if ((payload & 0xFFFF0000) == 0) {         // top 16 bits of second u32 are 0 if it's a response to KUMQUAT_GPU_PROTOCOL_HOST_COPY_INTO_COPY_BUFFER

          // uint32_t top, bottom = reverseBytes(stream_packets[4]), reverseBytes(stream_packets[3]);
          uint32_t top = stream_packets[4];
          uint32_t bottom = stream_packets[3];
          int resource_size =  (static_cast<uint64_t>(top) << 32) | static_cast<uint64_t>(bottom); // resource size is last u64

          // std::cout << "bytes: " << std::endl;
          // for (int i = 0; i<bytes_received_from_host; i++) {
          //   std::cout << static_cast<unsigned int>(txbuffer[i]) << " ";
          // }
          // std::cout << std::endl;

          // std::cout << "packets: " << std::endl;
          // for (int i = 0; i < stream_packet_send_total; i++) {
          //   std::cout << stream_packets[i] << " ";
          // }
          // std::cout << std::endl;

          // std::cout << "[bridge driver debug] top: " << top << " bottom: " << bottom << " size: " << resource_size << std::endl;

          int rutabaga_id = conn_to_rutabaga_id[connection_id];
          copy_to_dma(rutabaga_id, resource_size);
         }
      }
      */
        

    }
  } 


  // if there are packets to transmit from the last stream message
  if (stream_packet_send_total > 0) {
    uint32_t return_value = stream_packets[stream_packet_send_index++];

    // if we have sent all the packets, reset the count so that we can listen for another message
    if (stream_packet_send_index == stream_packet_send_total) {
      // std::cout << "[bridge driver] finished sending message to guest with size: " << stream_packet_send_total << std::endl;
      stream_packet_send_total = 0;
    }

    // std::cout << "[bridge driver] sending packet: " << return_value << std::endl;

    return return_value;
  }
    
  return std::nullopt;
  
}

void graphics_handler::put(uint32_t data) {

  /*
    graphics_handler::put() takes 4-byte packets from the bridge, accumulates them into a stream message, and sending the message to the host socket connection
  */
  

  if (stream_packet_receive_total == 0) {
    // this message should be a start-stream message

    uint8_t start1   = (data >> 24) & 0xFF;  
    uint8_t start2   = (data >> 16) & 0xFF;  
    uint8_t conn_id  = (data >> 8) & 0xFF;  
    uint8_t size     = data & 0xFF;          

    if ((data & 0xFFFF0000) != 0xFFFF0000) {
      std::cout << "[bridge driver] Error: first packet was not a start-stream packet: " << data << std::endl;
      return;
    }
    stream_packet_receive_total = (int) size + 1;

    rxbuffer[0] = conn_id;
    stream_packet_receive_count = 1;

    // std::cout << "[bridge driver] start-stream with ID: " << conn_id << " size: " << size << std::endl;

  } else {  
    // we are now accumulating a stream message for the socket

    for (int i = 0; i < 4; i++) {
      // get the i-th byte
      uint8_t b = data >> (8*i) & 0xFF;

      if (stream_packet_receive_count < stream_packet_receive_total) {
        rxbuffer[stream_packet_receive_count++] = b;
      } else {
        break;
      }
    }

    // once we get the total message assembled, we can write the message to the host2driver connection
    if (stream_packet_receive_count == stream_packet_receive_total) {   

      
      /*
      // if control header is KUMQUAT_GPU_PROTOCOL_HOST_COPY_FROM_COPY_BUFFER, pull N bytes from DMA and write into /tmp/copy-buffer-<rutabaga id>
      // bytes 1-4 should be 0x106 (control header)
      // bytes 9-12 and 13-16 are the resource_id
      // bytes 17-20 and 21-24 are the resource_size
      uint32_t control_header = (static_cast<uint32_t>(rxbuffer[4]) << 24) | 
                                (static_cast<uint32_t>(rxbuffer[3]) << 16) |
                                (static_cast<uint32_t>(rxbuffer[2]) << 8)  | 
                                (static_cast<uint32_t>(rxbuffer[1]));

      

      if (control_header == 0x106) {

        int resource_size = (static_cast<uint64_t>(rxbuffer[24]) << 56) | 
                            (static_cast<uint64_t>(rxbuffer[23]) << 48) |
                            (static_cast<uint64_t>(rxbuffer[22]) << 40) | 
                            (static_cast<uint64_t>(rxbuffer[21]) << 32) |
                            (static_cast<uint64_t>(rxbuffer[20]) << 24) | 
                            (static_cast<uint64_t>(rxbuffer[19]) << 16) |
                            (static_cast<uint64_t>(rxbuffer[18]) << 8)  | 
                            (static_cast<uint64_t>(rxbuffer[17]));

        int rutabaga_id = conn_to_rutabaga_id[rxbuffer[0]];

        copy_from_dma(rutabaga_id, resource_size);

      } 
      */

      // send the stream message to the socket
      if (::send(sockfd, rxbuffer, stream_packet_receive_total, 0) == -1) {
        // std::cout << "[bridge driver] error sending message to host: " << strerror(errno) << std::endl;
        perror("send");
      }

      stream_packet_receive_total = 0;

    }
  }
}

void graphics_handler::copy_to_dma(int rutabaga_id, int resource_size) {

  int fd = get_copy_buffer_fd(rutabaga_id);

  ssize_t bytes_read_from_file = pread(fd, dma_buffer, resource_size, 0);

  if (bytes_read_from_file != resource_size) {
    std::cout << "[bridge driver] error reading from copy-buffer: " << bytes_read_from_file << " wanted: " << resource_size <<std::endl;
  }

  int rc = pwrite(xdma_h2cfd, dma_buffer, resource_size, target_dram_addr);

  if (rc != resource_size) {
    std::cout << "[bridge driver] error writing to XDMA: " << rc << " wanted: " << resource_size <<std::endl;
  }
  // std::cout << "[bridge driver] copied to xdma: " << rc << " from copy-buffer-" << rutabaga_id << std::endl;
}

void graphics_handler::copy_from_dma(int rutabaga_id, int resource_size) {

  int rc = pread(xdma_c2hfd, dma_buffer, resource_size, target_dram_addr);

  if (rc != resource_size) {
    std::cout << "[bridge driver] error reading from XDMA: " << rc << " wanted: " << resource_size <<std::endl;
  }

  int fd = get_copy_buffer_fd(rutabaga_id);

  ssize_t bytes_written_to_file = pwrite(fd, dma_buffer, resource_size, 0);

  if (bytes_written_to_file != resource_size) {
    std::cout << "[bridge driver] error writing to copy-buffer: " << bytes_written_to_file << " wanted: " << resource_size <<std::endl;
  }

  // std::cout << "[bridge driver] copied from xdma: " << rc << " to copy-buffer-" << rutabaga_id << std::endl;
}

int graphics_handler::get_copy_buffer_fd(int rutabaga_id) {
  // open the host copy-buffer if needed
  if (copy_buffers.find(rutabaga_id) == copy_buffers.end()) {
    char copy_buffer_path[256];
    snprintf(copy_buffer_path, sizeof(copy_buffer_path), "/tmp/copy-buffer-%d", rutabaga_id);
    int fd = open(copy_buffer_path, O_RDWR | O_CREAT, 0666);
    if (fd < 0) {
        perror("Error opening file");
        std::cout << "[bridge driver] error opening copy-buffer at " << copy_buffer_path << std::endl;
        exit(EXIT_FAILURE);
    }

    copy_buffers[rutabaga_id] = fd;
  }
  return copy_buffers[rutabaga_id];
}

void graphics_handler::close() {

  // close socket
  ::close(sockfd);

  // close XDMA
  ::close(xdma_c2hfd);
  ::close(xdma_h2cfd);

  // close the copy-buffers
  for (const auto& pair : copy_buffers) {
    ::close(pair.second);
  }
}


graphics_handler::graphics_handler() {

  /*
    Set up the socket connection to the host-gfxstream host2driver helper server
  */

  // Create a socket
  sockfd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
  if (sockfd == -1) {
      perror("socket");
      // std::cout << "[bridge driver] Error creating socket" << std::endl;
  }

  int flags = fcntl(sockfd, F_GETFL, 0);
  fcntl(sockfd, F_SETFL, flags | O_NONBLOCK); 

  // Zero out the address structure
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

  // Connect to the server
  if (connect(sockfd, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
    perror("connect");
    ::close(sockfd);
    std::cout << "[bridge driver] Error connecting to server" << std::endl;
  } else {
    std::cout << "[bridge driver] Connected to " << SOCKET_PATH << std::endl;
  }

  stream_packet_send_total = 0;

  dma_buffer = (uint8_t*) malloc(100*1024*1024);
  xdma_h2cfd = open("/dev/xdma0_h2c_0", O_WRONLY);
  xdma_c2hfd = open("/dev/xdma0_c2h_0", O_RDONLY);

}

static std::unique_ptr<graphics_handler> create_handler(graphics_t* driver) {
  return std::make_unique<graphics_handler>();
}


graphics_t::graphics_t(simif_t &simif,
                const GRAPHICSBRIDGEMODULE_struct &mmio_addrs, 
                int graphicsno, 
                const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), 
    mmio_addrs(mmio_addrs), 
    handler(create_handler(this)) {}

graphics_t::~graphics_t() = default;

void graphics_t::send() {

  if (data.in.fire()) {                           // data.in.fire() is true if we have valid data and fifo is ready to accept
    write(mmio_addrs.in_bits, data.in.bits);      // write the data to the rxfifo.io.enq (send to the bridge)
    write(mmio_addrs.in_valid, data.in.valid);    // and mark it as valid 
  }
  if (data.out.fire()) {                          // data.out.fire() is true if valid and ready
    write(mmio_addrs.out_ready, data.out.ready);  // tell the bridge to dequeue the data from the fifo
  }
  
}

void graphics_t::recv() {
  data.in.ready = read(mmio_addrs.in_ready);    // check if the bridge ready to recieve data
  data.out.valid = read(mmio_addrs.out_valid);  // check if the data from the bridge is valid
  if (data.out.valid) {                         // if the data from the bridge is valid, read it into data.out
    data.out.bits = read(mmio_addrs.out_bits);
  }
}


void graphics_t::tick() {


  data.out.ready = true;                        // we are ready to receive data from outside
  data.in.valid = false;                        // the data we are sending to the bridge is not yet valid
  do {

    this->recv();                               // read anything coming from the bridge

    if (data.in.ready) {                        // if the bridge is ready to receive data
      if (auto bits = handler->get()) {         // get the packet incoming from handler
        data.in.bits = *bits;                   // write the bits and mark as valid
        data.in.valid = true;
      }
    }

    if (data.out.fire()) {                      // send the packet from the bridge out to handler
      handler->put(data.out.bits);
    }

    this->send();                               // send the data we wrote into data.in
    data.in.valid = false;                      // mark as invalid after sending
  } while (data.in.fire() || data.out.fire());  

}


void graphics_t::finish() {
  handler->close();
}




