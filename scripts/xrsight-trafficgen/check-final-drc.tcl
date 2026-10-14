# Read-only validation of the routed checkpoint; no implementation changes.
set_param general.maxThreads 4
open_checkpoint [lindex $argv 0]
report_drc -file [lindex $argv 1]
set errors [get_drc_violations -quiet -filter {SEVERITY == "Error" || SEVERITY == "Critical Warning"}]
puts "XRSIGHT_FINAL_DRC_ERRORS [llength $errors]"
close_design
if {[llength $errors] != 0} { exit 1 }
exit 0
