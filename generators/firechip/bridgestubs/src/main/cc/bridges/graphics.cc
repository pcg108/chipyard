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

#ifndef _WIN32
#include <unistd.h>

char graphics_t::KIND;

#endif

std::optional<uint32_t> graphics_handler::get() {
  /*
    graphics_handler::get() deals with reading from the socket connection for new stream messages and translating them into a series of 4-byte packets that can be sent over MMIO
    It takes multiple calls to get() to completely transmit a stream message
  */

  // if we don't have the host2driver connection yet, get that before doing anything else
  if (!client_connected) {
    if ((client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_len)) == -1) {
      std::cerr << "Accept failed: " << strerror(errno) << std::endl;
    }
    std::cout << "[bridge driver] Client connected" << std::endl;
    client_connected = true;
  }

  // if we are not currently transmitting a previous stream message, we can check if there is a new stream message to send
  if (stream_packet_send_total == 0) {
    ssize_t bytes_received_from_host = ::read(client_fd, txbuffer, BUFFER_SIZE - 1);
  
    if (bytes_received_from_host > 0) {

      // host2middle sends the connection_id as the first byte
      char connection_id = txbuffer[0];

      // construct the start-stream packet for the target as {16'b1, connection_id, size}
      uint32_t start_stream = static_cast<uint32_t>(0xFFFF);
      uint32_t id           = static_cast<uint32_t>(connection_id) & 0xFF;  
      uint32_t size         = static_cast<uint32_t>(bytes_received_from_host - 1) & 0xFF;

      start_stream = (start_stream << 16) | (id << 8) | size;

      int index = 0;
      stream_packets[index++] = start_stream;

      // construct the packets for the stream (break up the bytes into sets of 4-byte messages encoded as uint32_t)
      for (int i = 0; i < (bytes_received_from_host-1) / 4; i++) {
        uint32_t b0 = txbuffer[4*i + 0 + 1];
        uint32_t b1 = txbuffer[4*i + 1 + 1];
        uint32_t b2 = txbuffer[4*i + 2 + 1];
        uint32_t b3 = txbuffer[4*i + 3 + 1];

        stream_packets[index++] = (b3 << 24) | (b2 << 16) | (b1 << 8) | (b0);
      }

      // construct the last packet if total size isn't a multiple of 4
      if (4*index < bytes_received_from_host - 1) {

        uint32_t last_packet = 0;
        for (int i = bytes_received_from_host-1; i >= 4*index+1; i--) {
          uint32_t byte = static_cast<uint32_t>(txbuffer[i]) & 0xFF;  
          last_packet = (last_packet << 8) | byte;
        }
        stream_packets[index++] = last_packet;

      } 

      stream_packet_send_total = index;
      stream_packet_send_index = 0;
    }
  } 

  // if there are packets to transmit from the last stream message
  if (stream_packet_send_total > 0) {
    uint32_t return_value = stream_packets[stream_packet_send_index++];

    // if we have sent all the packets, reset the count so that we can listen for another message
    if (stream_packet_send_index == stream_packet_send_total) {
      stream_packet_send_total = 0;
    }

    return return_value;
  }
    
  return std::nullopt;
  
}

void graphics_handler::put(uint32_t data) {
  /*
    graphics_handler::put() deals with taking 4-byte packets from the bridge, accumulating them into a stream message, and sending the message to the host socket connection
  */
  
  if (stream_packet_receive_total == 0) {
    // this message should be a start-stream message

    char start1   = (data >> 24) & 0xFF;  
    char start2   = (data >> 16) & 0xFF;  
    char conn_id  = (data >> 8) & 0xFF;  
    char size     = data & 0xFF;          

    if (start1 != 0xFF || start2 != 0xFF) {
      std::cout << "[bridge driver] Error: first packet was not a start-stream packet" << std::endl;
      return;
    }
    stream_packet_receive_total = (int) size + 1;

    rxbuffer[0] = conn_id;
    stream_packet_receive_count = 1;

  } else {  
    // we have received a start-stream packet and are now accumulating a stream message for the socket

    for (int i = 0; i < 4; i++) {
      // get the i-th byte
      char b = data >> (8*i) && 0xFF;

      if (stream_packet_receive_count < stream_packet_receive_total) {
        rxbuffer[stream_packet_receive_count++] = b;
      } else {
        break;
      }
    }

    // once we get the total message assembled, we can write the message to the host2driver connection
    if (stream_packet_receive_count == stream_packet_receive_total) {   
      ::write(client_fd, rxbuffer, stream_packet_receive_total);
      stream_packet_receive_total = 0;
    }

  }
  
}

void graphics_handler::close() {
  if (client_connected) {
    ::close(client_fd);
  }
  
  ::close(server_fd);
  unlink(SOCKET_PATH);
}

graphics_handler::graphics_handler() {

  /*
    Set up the socket connection to the host gfxstream host2driver helper
  */

  // open socket server for host2driver helper
  if ((server_fd = socket(AF_UNIX, SOCK_STREAM, 0)) == -1) {
    std::cerr << "[bridge driver] Socket creation failed: " << strerror(errno) << std::endl;
  } else {
    std::cout << "[bridge driver] Started bridge driver server" << std::endl;
  }

  // Remove existing socket file
  unlink(SOCKET_PATH);

  // Configure server address
  memset(&server_addr, 0, sizeof(server_addr));
  server_addr.sun_family = AF_UNIX;
  strncpy(server_addr.sun_path, SOCKET_PATH, sizeof(server_addr.sun_path) - 1);

  // Bind socket to path
  if (bind(server_fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) == -1) {
    std::cerr << "[bridge driver] Bind failed: " << strerror(errno) << std::endl;
    ::close(server_fd);
  } else {
    std::cout << "[bridge driver] Bound socket to path" << std::endl;
  }

  chmod(SOCKET_PATH, 0777);

  // Start listening
  if (listen(server_fd, 5) == -1) {
    std::cerr << "Listen failed: " << strerror(errno) << std::endl;
    ::close(server_fd);
  }

  std::cout << "[bridge driver] Server listening on " << SOCKET_PATH << std::endl;
  client_connected = false;

  stream_packet_send_total = 0;


}

static std::unique_ptr<graphics_handler> create_handler() {
  return std::make_unique<graphics_handler>();
}


graphics_t::graphics_t(simif_t &simif,
                StreamEngine &stream,
                const GRAPHICSBRIDGEMODULE_struct &mmio_addrs, 
                int graphicsno, 
                const std::vector<std::string> &args,
                const int stream_to_cpu_idx,
                const int stream_to_cpu_depth,
                const int stream_from_cpu_idx,
                const int stream_from_cpu_depth)
    : streaming_bridge_driver_t(simif, stream, &KIND), 
    mmio_addrs(mmio_addrs), 
    handler(create_handler()),
    stream_to_cpu_idx(stream_to_cpu_idx),
    stream_from_cpu_idx(stream_from_cpu_idx), 
    stream_to_cpu_depth(stream_to_cpu_depth),
    stream_from_cpu_depth(stream_from_cpu_depth) 
    {
      read_buf = (uint8_t*) malloc(BUFWIDTH * stream_to_cpu_depth);
      write_buf = (uint8_t*) malloc(BUFWIDTH * stream_from_cpu_depth);
    }

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



/*

// read from target memory
write(mmio_addrs.stream_req_tx_bits, 192);
write(mmio_addrs.stream_req_tx_valid, 1);
uint32_t bytes_received_from_fpga = pull(stream_to_cpu_idx, 
      read_buf, 
      BUFWIDTH * 3, 
      BUFWIDTH * 3);


// write into target memory
write(mmio_addrs.stream_req_rx_bits, 128);
write(mmio_addrs.stream_req_rx_valid, 1);

memset(write_buf, 0xAA, BUFWIDTH);
memset(write_buf + BUFWIDTH, 0xBB, BUFWIDTH);
uint32_t bytes_sent_to_fpga = push(stream_from_cpu_idx,
                          write_buf,
                          BUFWIDTH *2,
                          BUFWIDTH *2);

*/




