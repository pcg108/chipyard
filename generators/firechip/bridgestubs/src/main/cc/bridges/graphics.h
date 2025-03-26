// See LICENSE for license details

#ifndef __GRAPHICS_H
#define __GRAPHICS_H

#include "bridges/serial_data.h"
#include "core/bridge_driver.h"
#include "core/stream_engine.h"

#include <cstdint>
#include <memory>
#include <optional>
#include <signal.h>
#include <string>
#include <vector>
#include <fstream>

#include <cstring>
#include <sys/socket.h>
#include <sys/un.h>
#include <algorithm>

#include <cctype>

#include <unordered_map>

/**
 * Structure carrying the addresses of all fixed MMIO ports.
 *
 * This structure is instantiated when all bridges are populated based on
 * the target configuration.
 */
struct GRAPHICSBRIDGEMODULE_struct {
  uint64_t out_bits;
  uint64_t out_valid;
  uint64_t out_ready;
  uint64_t in_bits;
  uint64_t in_valid;
  uint64_t in_ready;
  
  uint64_t guest_transmit;
  uint64_t host_transmit;
};

class graphics_handler {
  public:
    virtual ~graphics_handler() = default;

    graphics_handler();
  
    std::optional<uint32_t> get();
    void put(uint32_t data);
    void close();

  private:
    const char* SOCKET_PATH = "/tmp/kumquat-gpu-1";
    int sockfd;
    struct sockaddr_un addr;
    int BUFFER_SIZE = 1024;

    uint8_t txbuffer[1024];
    uint8_t rxbuffer[1024];

    uint32_t stream_packets[128];

    int stream_packet_send_total;
    int stream_packet_send_index;
    
    int stream_packet_receive_total;
    int stream_packet_receive_count;

    std::unordered_map<uint8_t, int> conn_to_rutabaga_id;

    // copy buffer file descriptors
    std::unordered_map<int, int> copy_buffers;

    // XDMA file descriptors
    int xdma_h2cfd;
    int xdma_c2hfd;

    // FPGA host memory size
    unsigned long long target_dram_addr = (0x88000000 + 0x380000000) % 0x400000000;

    uint8_t* dma_buffer;

    void copy_from_dma(int rutabaga_id, int resource_size);
    void copy_to_dma(int rutabaga_id, int resource_size);
    int get_copy_buffer_fd(int rutabaga_id);
};


class graphics_t final : public bridge_driver_t {// public streaming_bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  graphics_t(simif_t &simif,
        const GRAPHICSBRIDGEMODULE_struct &mmio_addrs,
        int graphicsno,
        const std::vector<std::string> &args);

  ~graphics_t() override;

  void tick() override;
  void finish() override;


private:
  const GRAPHICSBRIDGEMODULE_struct mmio_addrs;
  std::unique_ptr<graphics_handler> handler;

  serial_data_t<uint32_t> data;

  void send();
  void recv();

};

#endif // __GRAPHICS_H
