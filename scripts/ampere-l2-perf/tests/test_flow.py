import importlib.util
import sys
import unittest
from pathlib import Path
PKG=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(PKG))
from controller import validation_gate
from common import ValidationFailure

spec=importlib.util.spec_from_file_location('utilization',PKG/'check-utilization.py')
utilization=importlib.util.module_from_spec(spec);spec.loader.exec_module(utilization)
spec=importlib.util.spec_from_file_location('snapshots',PKG/'check-snapshots.py')
snapshots=importlib.util.module_from_spec(spec);spec.loader.exec_module(snapshots)

class BuildGateTest(unittest.TestCase):
    def test_missing_and_failed_validation_cannot_build(self):
        planned=[{'name':'focused-rtl'},{'name':'rodinia'},{'name':'bitstream'},{'name':'package'}]
        for state in ({'stages':{}},{'stages':{'focused-rtl':{'status':'passed'},'rodinia':{'status':'failed'}}}):
            with self.assertRaises(ValidationFailure):validation_gate(state,planned)
        valid={'stages':{'focused-rtl':{'status':'passed'},'rodinia':{'status':'passed'}}}
        self.assertEqual(validation_gate(valid,planned)['status'],'passed')

    def test_resource_accounting_combines_half_brams(self):
        text='xcu250\nDesign State : Synthesized\n| overall_fpga_top | (top) | 100 | 90 | 5 | 5 | 200 | 10 | 3 | 4 | 2 |\n'
        result=utilization.parse(text)
        self.assertEqual(result['BRAM36']['used'],11.5)
        self.assertEqual(result['LUT']['available'],1728000)

    def test_missing_or_duplicate_top_resource_row_rejected(self):
        row='| overall_fpga_top | (top) | 100 | 90 | 5 | 5 | 200 | 10 | 3 | 4 | 2 |\n'
        for text in ('xcu250 Synthesized', 'xcu250 Synthesized\n'+row+row):
            with self.assertRaises(ValueError):utilization.parse(text)

    def test_wrong_device_or_design_state_rejected(self):
        with self.assertRaises(ValueError):utilization.parse('xcu250 Routed')
        with self.assertRaises(ValueError):utilization.parse('xcu200 Synthesized')

    def test_carry_in_and_out_accounting_and_corruption(self):
        counters=[]
        for name in list(snapshots.DEMAND)+['demand_completed','instrumentation_errors']+[
                r+'_'+kind for r in snapshots.REQUESTERS for kind in ('accepted','completed')]:
            counters.append({'bank':0,'name':name,'start':0,'end':0,'delta':0,'gauge':False})
        lookup={v['name']:v for v in counters}
        counters.append({'bank':0,'name':'outstanding_requests','start':3,'end':2,'gauge':True})
        # A carry-in request completes without a new request in this interval.
        for name in ('demand_completed','trafficgen_completed'):
            lookup[name].update(end=1,delta=1)
        snapshots.check_interval(counters)
        counters[-1]['end']=3
        with self.assertRaises(ValidationFailure):snapshots.check_interval(counters)
        counters[-1]['end']=2;lookup['trafficgen_completed'].update(end=0,delta=0)
        with self.assertRaises(ValidationFailure):snapshots.check_interval(counters)
        lookup['trafficgen_completed'].update(end=1,delta=1)
        lookup['instrumentation_errors']['end']=1
        with self.assertRaises(ValidationFailure):snapshots.check_interval(counters)

if __name__=='__main__':unittest.main()
