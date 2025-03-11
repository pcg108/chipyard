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

#define BUFWIDTH streaming_bridge_driver_t::STREAM_WIDTH_BYTES

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

  uint64_t stream_req_rx_bits;
  uint64_t stream_req_rx_valid;
  uint64_t stream_req_rx_ready;

  uint64_t stream_req_tx_bits;
  uint64_t stream_req_tx_valid;
  uint64_t stream_req_tx_ready;
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
    const int BUFFER_SIZE = 1024;
    int server_fd, client_fd;
    struct sockaddr_un server_addr, client_addr;
    socklen_t client_len = sizeof(client_addr);
    char txbuffer[1024];

    uint32_t stream_packets[128];

    int stream_packet_send_total;
    int stream_packet_send_index;
    
    char rxbuffer[1024];
    int stream_packet_receive_total;
    int stream_packet_receive_count;
    

    bool client_connected;
};


class graphics_t final : public streaming_bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  graphics_t(simif_t &simif,
        StreamEngine &stream,
        const GRAPHICSBRIDGEMODULE_struct &mmio_addrs,
        int graphicsno,
        const std::vector<std::string> &args,
        int stream_to_cpu_idx,
        int stream_to_cpu_depth,
        int stream_from_cpu_idx,
        int stream_from_cpu_depth);

  ~graphics_t() override;

  void tick() override;
  void finish() override;

private:
  const GRAPHICSBRIDGEMODULE_struct mmio_addrs;
  std::unique_ptr<graphics_handler> handler;

  serial_data_t<char> data;

  void send();
  void recv();

  const int stream_to_cpu_idx;
  const int stream_from_cpu_idx;
  const int stream_to_cpu_depth;
  const int stream_from_cpu_depth;

  uint8_t* read_buf;
  uint8_t* write_buf;

};

#endif // __GRAPHICS_H
