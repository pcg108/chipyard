import importlib.util
import copy
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
spec=importlib.util.spec_from_file_location('smoke',PKG/'smoke.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
spec=importlib.util.spec_from_file_location('set_experiments',PKG/'run-set-experiments.py')
sets=importlib.util.module_from_spec(spec);spec.loader.exec_module(sets)

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

    def test_interval_summary_must_match_frozen_raw_endpoints_and_schema(self):
        schema={'schema_version':1,'counter_width':64,'counters':[
            {'bank':0,'requester':'trafficgen','name':'accepted','unit':'transactions','gauge':False},
            {'bank':0,'requester':'all','name':'outstanding','unit':'entries','gauge':True}]}
        records={}
        for sequence,cycle,accepted,outstanding in ((0,100,12,3),(1,200,19,2)):
            records[sequence]=[]
            for counter,value in zip(schema['counters'],(accepted,outstanding)):
                records[sequence].append({'schema_version':'1','sequence':str(sequence),'window_id':'9',
                    'target_cycle':str(cycle),'bank':'0','requester':counter['requester'],
                    'counter':counter['name'],'unit':counter['unit'],
                    'kind':'gauge' if counter['gauge'] else 'cumulative','value':str(value)})
        interval={'window_id':9,'start_sequence':0,'end_sequence':1,'start_cycle':100,'end_cycle':200,
                  'counters':[dict(schema['counters'][0],start=12,end=19,delta=7),
                              dict(schema['counters'][1],start=3,end=2)]}
        snapshots.check_raw_endpoints(interval,records,schema)
        # A fabricated summary can retain correct delta arithmetic and still
        # report the wrong absolute vectors; only binding raw endpoints catches it.
        changed=copy.deepcopy(interval);changed['counters'][0].update(start=22,end=29)
        with self.assertRaises(ValidationFailure):snapshots.check_raw_endpoints(changed,records,schema)
        for field,value in (('start_cycle',101),('window_id',10),('end_sequence',2)):
            changed=copy.deepcopy(interval);changed[field]=value
            with self.assertRaises(ValidationFailure):snapshots.check_raw_endpoints(changed,records,schema)
        for field,value in (('gauge',False),('unit','transactions'),('requester','rocket')):
            changed=copy.deepcopy(interval);changed['counters'][1][field]=value
            with self.assertRaises(ValidationFailure):snapshots.check_raw_endpoints(changed,records,schema)
        changed=copy.deepcopy(records);changed[1][1]['kind']='cumulative'
        with self.assertRaises(ValidationFailure):snapshots.check_raw_endpoints(interval,changed,schema)

    def test_backend_coverage_requires_every_backend_and_correct_classification(self):
        requesters=('rocket','saturn','gemmini_int8','trafficgen','other')
        counters=[]
        for requester in requesters:
            for event,value in (('accepted',2),('completed',1),('tag_hits',1),('tag_misses',0)):
                counters.append({'bank':0,'name':requester+'_'+event,'gauge':False,
                                 'delta':value if requester!='other' else 0})
        counters.extend([{'bank':0,'name':'tag_hits','gauge':False,'delta':4},
                         {'bank':0,'name':'tag_misses','gauge':False,'delta':0}])
        summary={'intervals':[{'kind':'window','counters':counters}]}
        # Acceptance need not equal classification inside an arbitrary window.
        self.assertEqual(len(smoke.backend_activity(summary)),4)
        counters[-2]['delta']=5
        with self.assertRaises(ValidationFailure):smoke.backend_activity(summary)
        counters[-2]['delta']=4
        next(c for c in counters if c['name']=='gemmini_int8_completed')['delta']=0
        with self.assertRaises(ValidationFailure):smoke.backend_activity(summary)

    def test_serialization_requires_a_measured_increase(self):
        accepted='same_set_other_line_secondary_accepted'
        blocked='same_set_other_line_secondary_blocked_cycles'
        dispersed={accepted:10,blocked:20}
        with self.assertRaises(ValidationFailure):sets.compare_serialization(dispersed,dict(dispersed))
        with self.assertRaises(ValidationFailure):sets.compare_serialization(dispersed,{accepted:9,blocked:19})
        result=sets.compare_serialization(dispersed,{accepted:11,blocked:0})
        self.assertEqual(result[accepted]['increase'],1)
        self.assertEqual(result[blocked]['increase'],-20)

if __name__=='__main__':unittest.main()
