#include "VCache.h"
#include "verilated.h"
#include <array>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <map>
#include <stdexcept>
#include <string>
#include <sstream>
#include <iostream>
constexpr uint32_t COUNTER=0x84dc8790, BASE=COUNTER&~63u;
struct Beat {int opcode,param,source; uint64_t due; std::array<uint32_t,4> data; bool last;};
struct Resp {int cmd; uint64_t data,store; bool replay;};
struct Sim {
 VCache d; uint64_t cy=0; int delay=5; bool exclusive=true,verbose=false;
 std::map<uint32_t,uint32_t> mem; std::deque<Beat> beats;std::deque<Resp> responses;std::deque<std::string> log;
 int vphase=0,vdone=0,vmode=0;int64_t vecAt=-1;uint64_t vexpected=0,vdata=0;bool vwrite=false;unsigned vecfires=0,vecnacks=0;
 bool nack=false,fire=false,xcpt=false,grantOutstanding=false,probeSent=false,probeDone=false;int probeParam=2;int64_t probeAt=-1;unsigned cbeat=0;unsigned grants=0,replays=0,writes=0;
 void event(std::string s){std::string l=std::to_string(cy)+" "+s;if(verbose)std::cout<<l<<"\n";log.push_back(l);if(log.size()>400)log.pop_front();}
 Sim(int lat,bool ex,bool v=false):delay(lat),exclusive(ex),verbose(v){
 #include "zero_inputs.h"
 d.io_cpu_req_bits_phys=0;d.io_cpu_req_bits_dprv=3;d.io_ptw_req_ready=1;d.auto_out_c_ready=1;d.auto_out_e_ready=1;
 mem[COUNTER+8]=0x88429800;mem[COUNTER+12]=0;
 d.reset=1;for(int i=0;i<10;i++)step();d.reset=0;for(int i=0;i<100;i++)step();log.clear();
 }
 void step(){
 if(vecAt>=0&&cy>=uint64_t(vecAt)&&vphase==0&&vdone<16){vphase=1;vwrite=!(vdone&1);vdata=0x1122334400000000ull+unsigned(vdone);}
 d.io_vec_req_valid=vphase==1;d.io_vec_req_bits_addr=COUNTER-8+(vmode?4096:0);d.io_vec_req_bits_cmd=vwrite?1:0;d.io_vec_req_bits_size=3;d.io_vec_req_bits_dprv=3;d.io_vec_req_bits_tag=7;d.io_vec_req_bits_phys=0;
 d.io_vec_s1_data_data=vphase==2?vdata:0xdeadbeefcafebabeull;d.io_vec_s1_data_mask=255;
 d.clock=0;d.auto_out_a_ready=beats.empty()&&(!probeSent||probeDone);d.auto_out_d_valid=!beats.empty()&&beats.front().due<=cy&&(!probeSent||probeDone);
 if(!beats.empty()){auto &b=beats.front();d.auto_out_d_bits_opcode=b.opcode;d.auto_out_d_bits_param=b.param;d.auto_out_d_bits_source=b.source;d.auto_out_d_bits_size=6;d.auto_out_d_bits_sink=0;for(int j=0;j<4;j++)d.auto_out_d_bits_data[j]=b.data[j];}
 d.auto_out_b_valid=probeAt>=0&&cy>=uint64_t(probeAt)&&!probeSent&&!grantOutstanding;d.auto_out_b_bits_address=BASE;d.auto_out_b_bits_param=probeParam;d.auto_out_b_bits_size=6;d.auto_out_b_bits_source=0;
 d.eval();fire=d.io_cpu_req_valid&&d.io_cpu_req_ready;nack=d.io_cpu_s2_nack;
 bool af=d.auto_out_a_valid&&d.auto_out_a_ready, df=d.auto_out_d_valid&&d.auto_out_d_ready;
 if(df){auto b=beats.front();event("D op="+std::to_string(b.opcode)+" last="+std::to_string(b.last));beats.pop_front();}
 if(d.auto_out_e_valid&&d.auto_out_e_ready)grantOutstanding=false;
 if(af){grantOutstanding=true;int op=d.auto_out_a_bits_opcode,par=d.auto_out_a_bits_param,src=d.auto_out_a_bits_source;uint32_t addr=d.auto_out_a_bits_address;event("A op="+std::to_string(op)+" param="+std::to_string(par)+" addr="+std::to_string(addr));if(op!=6&&op!=7)throw std::runtime_error("unsupported A opcode");int grant=(par!=0||exclusive)?0:1;for(int i=0;i<4;i++){Beat b{5,grant,src,cy+unsigned(delay),{},i==3};for(int j=0;j<4;j++)b.data[j]=mem[(addr&~63u)+i*16+j*4];beats.push_back(b);}grants++;}
 if(d.auto_out_b_valid&&d.auto_out_b_ready){probeSent=true;event("B accepted param="+std::to_string(probeParam));}
 if(d.auto_out_c_valid&&d.auto_out_c_ready){int op=d.auto_out_c_bits_opcode;uint32_t addr=d.auto_out_c_bits_address;event("C op="+std::to_string(op)+" beat="+std::to_string(cbeat));bool has=op&1; if(has){for(int j=0;j<4;j++)mem[(addr&~63u)+cbeat*16+j*4]=d.auto_out_c_bits_data[j];}bool last=!has||cbeat==3;if(last){cbeat=0;if(op==4||op==5)probeDone=true;else if(op==6||op==7)beats.push_back(Beat{6,0,int(d.auto_out_c_bits_source),cy+unsigned(delay),{},true});else throw std::runtime_error("unsupported C opcode");}else cbeat++;}
 if(fire)event("REQ cmd="+std::to_string(d.io_cpu_req_bits_cmd)+" addr="+std::to_string(d.io_cpu_req_bits_addr));
 if(nack)event("NACK");
 if(d.dbg_s2replay){replays++;event("REPLAY addr="+std::to_string(d.dbg_s2addr)+" data="+std::to_string(d.dbg_s2data));}
 if(d.dbg_s3valid){writes++;event("WRITE addr="+std::to_string(d.dbg_s3addr)+" data="+std::to_string(d.dbg_s3data)+" way="+std::to_string(d.dbg_s3way));}
 if(d.io_cpu_resp_valid){responses.push_back(Resp{int(d.io_cpu_resp_bits_cmd),d.io_cpu_resp_bits_data,d.io_cpu_resp_bits_store_data,bool(d.io_cpu_resp_bits_replay)});event("RESP cmd="+std::to_string(d.io_cpu_resp_bits_cmd)+" data="+std::to_string(d.io_cpu_resp_bits_data)+" store="+std::to_string(d.io_cpu_resp_bits_store_data));}
 xcpt=d.io_cpu_s2_xcpt_ae_ld||d.io_cpu_s2_xcpt_ae_st;
 if(d.io_vec_req_valid&&d.io_vec_req_ready){vecfires++;event("VREQ cmd="+std::to_string(d.io_vec_req_bits_cmd));vphase=2;}
 else if(vphase==2)vphase=3;
 else if(vphase==3&&d.io_vec_s2_nack){vecnacks++;vphase=1;event("VNACK");}
 else if(vphase==3)vphase=4;
 if(d.io_vec_resp_valid){if(vphase!=4)throw std::runtime_error("unexpected vector-side response phase "+std::to_string(vphase));if(d.io_vec_resp_bits_tag!=7)throw std::runtime_error("wrong vector response tag");if(!vwrite&&d.io_vec_resp_bits_data_raw!=vexpected)throw std::runtime_error("vector-side load mismatch");if(vwrite)vexpected=vdata;vdone++;vphase=0;event("VRESP count="+std::to_string(vdone));}
 d.clock=1;d.eval();cy++;
 }
 Resp request(uint32_t addr,int cmd,uint64_t data=0,int size=2,bool kill=false){
 for(int attempt=0;attempt<100;attempt++){
 d.io_cpu_req_bits_addr=addr;d.io_cpu_req_bits_cmd=cmd;d.io_cpu_req_bits_size=size;d.io_cpu_req_bits_tag=0;d.io_cpu_req_valid=1;
 int t=0;do{step();if(++t>500)throw std::runtime_error("req ready timeout");}while(!fire);
 d.io_cpu_req_valid=0;d.io_cpu_s1_data_data=data;d.io_cpu_s1_kill=kill;step();d.io_cpu_s1_kill=0;d.io_cpu_s1_data_data=0xa5a5a5a55a5a5a5aull;step();
 if(kill){for(int i=0;i<10;i++)step();return Resp{cmd,0,0,false};}
 if(nack){step();continue;}
 if(xcpt)throw std::runtime_error("access fault on valid request");
 for(int i=0;responses.empty()&&i<500;i++)step();if(responses.empty())throw std::runtime_error("response timeout");auto r=responses.front();responses.pop_front();if(r.cmd!=cmd)throw std::runtime_error("response command mismatch");return r;
 }throw std::runtime_error("too many nacks");
 }
 void idle(int n){while(n--)step();}
 void dump(){for(auto &s:log)std::cout<<s<<"\n";}
};
int main(int argc,char**argv){Verilated::commandArgs(argc,argv);bool neg=false,verbose=false;for(int i=1;i<argc;i++){neg|=std::string(argv[i])=="--negative";verbose|=std::string(argv[i])=="--verbose";}int tests=0,failed=0;unsigned allreplay=0,allwrites=0,allvec=0,allvnack=0;
 for(int vecOffset:{-4,0,1,2,4,8,16})for(int vm:{0,1})for(bool exclusive:{true,false})for(int delay:{1,5,20})for(int pp:{1,2})for(int offset=-4;offset<=35;offset++){
 Sim s(delay,exclusive,verbose);try{
 auto r=s.request(COUNTER,0);if(r.data!=0)throw std::runtime_error("initial value nonzero");s.idle(15);
 s.probeParam=pp;s.probeAt=s.cy+10+offset;s.vecAt=s.cy+10+vecOffset;s.vmode=vm;s.idle(10);
 s.request(COUNTER,1,0x0000000100000001ull,2,neg);
 auto adjacent=s.request(COUNTER+8,0,0,3);if(adjacent.data!=0x88429800ull)throw std::runtime_error("adjacent word corrupt");s.idle(125);
 for(int i=0;s.vdone<16&&i<5000;i++)s.step();if(s.vdone!=16)throw std::runtime_error("vector traffic timeout");
 auto result=s.request(COUNTER,0);if(result.data!=1)throw std::runtime_error("counter expected 1 actual "+std::to_string(result.data));
 if(!s.probeSent||!s.probeDone)throw std::runtime_error("probe not completed");
 }catch(const std::exception&e){failed++;std::cout<<"FAIL vecOffset="<<vecOffset<<" vmode="<<vm<<" exclusive="<<exclusive<<" delay="<<delay<<" probe="<<pp<<" offset="<<offset<<" error="<<e.what()<<"\n";if(failed<=3)s.dump();}
 tests++;allreplay+=s.replays;allwrites+=s.writes;allvec+=s.vdone;allvnack+=s.vecnacks;
 }
 std::cout<<"RESULT tests="<<tests<<" failures="<<failed<<" replays="<<allreplay<<" writes="<<allwrites<<" vector_completed="<<allvec<<" vector_nacks="<<allvnack<<" negative="<<neg<<"\n";return neg?(failed==tests?0:2):(failed?1:0);
}
