# Called only after finish-recovery.py validates the final implementation reports.
if {[llength $argv] != 2} {error "Expected validated checkpoint and output directory"}
if {[version -short] ne "2022.1"} {error "Expected Vivado 2022.1"}
set_param general.maxThreads 4
set checkpoint [file normalize [lindex $argv 0]]
set output [file normalize [lindex $argv 1]]
open_checkpoint $checkpoint
if {[get_property PART [current_design]] ne "xcu250-figd2104-2L-e"} {error "Wrong FPGA part"}
if {[get_property CONFIG_MODE [current_design]] ne "SPIx4"} {error "Wrong configuration mode"}
if {[get_property BITSTREAM.CONFIG.SPI_BUSWIDTH [current_design]] != 4} {error "Wrong SPI width"}
# write_bitstream performs its normal DRC checks; no severity overrides are used.
write_bitstream [file join $output firesim.bit]
close_design
write_cfgmem -format mcs -interface SPIx4 -size 1024 \
    -loadbit "up 0x01002000 [file join $output firesim.bit]" \
    -verbose [file join $output firesim.mcs]
exit
