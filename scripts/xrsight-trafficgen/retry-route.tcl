# Recovery candidate only. Run through retry-route.py after restart approval.
if {[llength $argv] != 2} {error "Expected checkpoint and output directory"}
if {[version -short] ne "2022.1"} {error "This recovery was checked for Vivado 2022.1 only"}
set checkpoint [file normalize [lindex $argv 0]]
set output [file normalize [lindex $argv 1]]
set_param general.maxThreads 4
# Internal 2022.1 parameter: queried and set successfully in a no-design session.
# This changes an optimization choice; it does not remove timing constraints.
set_param route.optimizeLeafClkProgDlys false
if {[get_param route.optimizeLeafClkProgDlys] != 0} {error "Leaf clock optimization is still enabled"}
set reports [file join $output vivado_proj reports]
set run_dir [file join $output vivado_proj firesim.runs impl_1]
file mkdir $reports $run_dir
open_checkpoint $checkpoint
route_design -directive Explore -tns_cleanup
# Save routing before any further optimization so another failure loses less work.
write_checkpoint [file join $run_dir overall_fpga_top_routed.dcp]
report_timing_summary -file [file join $reports routing_timing_summary.rpt]
report_route_status -file [file join $reports routing_status.rpt]
phys_opt_design -directive AggressiveExplore
write_checkpoint [file join $run_dir overall_fpga_top_postroute_physopt.dcp]
report_timing_summary -file [file join $reports final_timing_summary.rpt]
report_route_status -file [file join $reports final_route_status.rpt]
report_bus_skew -file [file join $run_dir overall_fpga_top_bus_skew_postroute_physopted.rpt]
report_timing -max_paths 20 -file [file join $reports final_critical_paths.rpt]
report_utilization -hierarchical -hierarchical_percentages -file [file join $reports final_utilization.rpt]
report_ram_utilization -include_lutram -file [file join $reports final_ram_utilization.rpt]
report_clock_utilization -file [file join $reports final_clock_utilization.rpt]
report_drc -file [file join $reports final_drc.rpt]
close_design
# retry-route.py validates reports before marking this candidate ready for emission.
exit
