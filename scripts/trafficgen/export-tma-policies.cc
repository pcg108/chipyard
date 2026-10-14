// Decode complete policy snapshots with the untouched model's official schema.
#include "socket_protocol.h"
#include <boost/archive/binary_iarchive.hpp>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <regex>
#include <stdexcept>

int main(int argc, char **argv) {
  try {
    if(argc!=3) throw std::runtime_error("usage: export-tma-policies BRIDGE_LOG_DIR OUTPUT_JSONL");
    std::map<std::uint64_t,std::filesystem::path> paths;
    const std::regex pattern("tma_policy_([0-9]+)\\.bin");
    for(const auto &entry:std::filesystem::directory_iterator(argv[1])) {
      std::smatch match; const auto name=entry.path().filename().string();
      if(std::regex_match(name,match,pattern)) paths.emplace(std::stoull(match[1]),entry.path());
    }
    if(paths.empty()) throw std::runtime_error("no complete policy snapshots");
    std::ofstream out(argv[2]);
    for(const auto &[round,path]:paths) {
      std::ifstream input(path,std::ios::binary); boost::archive::binary_iarchive archive(input);
      GPU::TmaWakePolicy policy; archive >> policy;
      out << "{\"round\":" << round << ",\"groups\":[";
      bool comma=false;
      for(const auto &g:policy.groups) {
        if(comma) out << ',';
        comma=true;
        out << "{\"launch\":" << g.launchId << ",\"cta\":" << g.destinationCta
          << ",\"barrier\":" << g.barrierAddress << ",\"phase\":" << g.phase
          << ",\"transfer\":" << g.transferId << ",\"conservative\":" << g.conservative
          << ",\"ready_cycle\":" << g.readyCycle << ",\"requests\":[";
        bool memberComma=false;
        for(const auto &id:g.requests) {
          if(memberComma) out << ',';
          memberComma=true;
          out << '[' << id.launchId << ',' << id.requestUid << ']';
        }
        out << "]}";
      }
      out << "],\"outstandingRequests\":["; comma=false;
      for(const auto &id:policy.outstandingRequests) {
        if(comma) out << ',';
        comma=true;
        out << '[' << id.launchId << ',' << id.requestUid << ']';
      }
      out << "]}\n";
    }
    if(!out) throw std::runtime_error("failed to write decoded policies");
    std::cout << "Decoded " << paths.size() << " complete policy snapshots\n";
  } catch(const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
