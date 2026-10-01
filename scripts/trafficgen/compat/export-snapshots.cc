// Build against the exact producer's gpu_model headers/library, then decode its
// Boost snapshots. No inference from PNGs or CSV aggregate request counts.
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <regex>
#include <stdexcept>
#include <vector>
#include <boost/archive/binary_iarchive.hpp>
#include "round_logging.h"

namespace fs = std::filesystem;
template<class T> auto launch(const T &x, int) -> decltype(x.launchId) { return x.launchId; }
template<class T> std::uint64_t launch(const T &, long) { return 0; }
template<class T> auto registry(const T &x, int) -> decltype(x.registryId) { return x.registryId; }
template<class T> std::uint64_t registry(const T &, long) { return 0; }
template<class T> T read(const fs::path &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("missing snapshot: " + path.string());
    boost::archive::binary_iarchive archive(in);
    T result;
    archive >> result;
    return result;
}
int main(int argc, char **argv) {
    try {
        if (argc != 3) throw std::runtime_error("usage: export-snapshots ROUND_LOG_ROOT NEW_OUTPUT_DIR");
        const fs::path root(argv[1]), out(argv[2]);
        if (!fs::create_directory(out)) throw std::runtime_error("output directory must not exist");
        std::vector<fs::path> dirs;
        const std::regex pattern("round_[0-9]+");
        for (const auto &entry : fs::directory_iterator(root))
            if (entry.is_directory() && std::regex_match(entry.path().filename().string(), pattern))
                dirs.push_back(entry.path());
        std::sort(dirs.begin(), dirs.end(), [](const auto &a, const auto &b) {
            return std::stoull(a.filename().string().substr(6)) < std::stoull(b.filename().string().substr(6));
        });
        if (dirs.empty()) throw std::runtime_error("no round snapshots");
        std::ofstream scheduled(out / "scheduled.csv"), issued(out / "issued.csv"),
            rounds(out / "rounds.csv"), completed(out / "completed.csv"),
            blocked(out / "blocked.csv"), reservations(out / "reservations.csv");
        scheduled << "round,order,launch_id,registry_id,request_uid,address,is_write,sm_id,scheduler_id,warp_id,bundle_id,wake_relevant,cycle,subpartition,mask\n";
        issued << "round,order,launch_id,request_uid,address,is_write,cycle\n";
        rounds << "round,min_issue_cycle,end_cycle,scheduled,issued,completed\n";
        completed << "round,order,bundle_id\n";
        blocked << "round,launch_id,sm_id,scheduler_id,warp_id\n";
        reservations << "round,cycle,subpartition\n";
        for (const auto &dir : dirs) {
            const auto round = std::stoull(dir.filename().string().substr(6));
            using namespace GPU::RoundLogging;
            const auto accesses = read<AllL2TraceStepsSnapshot>(dir / "all_l2_trace_steps.bin");
            const auto points = read<IssuedAccessesSnapshot>(dir / "issued_accesses.bin");
            const auto bundles = read<CompletedBundleIdsSnapshot>(dir / "completed_bundle_ids.bin");
            const auto deadline = read<MinIssueCycleSnapshot>(dir / "min_issue_cycle.bin");
            const auto end = read<CurrentCycleAfterIssueSnapshot>(dir / "current_cycle_after_issue.bin");
            std::size_t order = 0;
            for (const auto &a : accesses.steps) {
                scheduled << round << ',' << order++ << ',' << launch(a, 0) << ',' << registry(a, 0)
                    << ',' << a.mUniqueId << ',' << a.mAddress << ',' << a.mIsWrite << ',' << a.smId
                    << ',' << a.schedulerId << ',' << a.warpId << ',' << a.mBundleId << ','
                    << a.mWakeRelevantBundle << ',' << a.mCycleCount << ',' << a.mSubpartition
                    << ',' << a.mMask << '\n';
            }
            order = 0;
            for (const auto &p : points.issuedAccesses)
                issued << round << ',' << order++ << ',' << launch(p, 0) << ',' << p.requestUid
                    << ',' << p.address << ',' << p.isWrite << ',' << p.cycleIssued << '\n';
            order = 0;
            for (const auto id : bundles.completedBundleIds) completed << round << ',' << order++ << ',' << id << '\n';
            rounds << round << ',' << deadline.minIssueCycle << ',' << end.currentCycleAfterIssue
                << ',' << accesses.steps.size() << ',' << points.issuedAccesses.size() << ','
                << bundles.completedBundleIds.size() << '\n';
            for (const auto &w : read<BlockedWarpIdsSnapshot>(dir / "blocked_warp_ids.bin").blockedWarpIds)
                blocked << round << ',' << launch(w, 0) << ',' << w.smId << ',' << w.schedulerId << ',' << w.warpId << '\n';
            for (const auto &[cycle, partitions] : read<ReservedSubpartitionsSnapshot>(dir / "reserved_subpartitions.bin").reservationsByCycle)
                for (const auto partition : partitions) reservations << round << ',' << cycle << ',' << partition << '\n';
            // Preserve observed lane assignments without reconstructing the driver.
            if (fs::exists(dir / "lane_assignments.csv"))
                fs::copy_file(dir / "lane_assignments.csv", out / (dir.filename().string() + "_lanes.csv"));
        }
        std::ofstream(out / "source.txt") << fs::absolute(root).string() << '\n';
        std::cout << "Decoded " << dirs.size() << " rounds to " << out << '\n';
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
