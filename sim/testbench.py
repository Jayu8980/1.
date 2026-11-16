#!/usr/bin/env python3
"""SPICE test bench for the Splendor Plus smart regulator rectifier (Rev C).

Builds one ngspice netlist per loading/fault condition, runs them in parallel,
and writes sim/results.json with pass/fail verdicts and down-sampled waveforms.

Run:  python3 sim/testbench.py            half-wave Rev C  -> sim/results.json
      python3 sim/testbench.py --bridge   full-wave bridge -> sim/results_bridge.json
      (needs ngspice on PATH and numpy; add test ids such as T03 to run only those)

Modelling notes
- Stator: two coils sharing one rotor phase (W charge coil, Y lighting coil),
  each an EMF proportional to rpm behind series L and R. 6 pole pairs, so
  electrical frequency = rpm / 10. Values are typical of a 100 cc magneto,
  not measured on a bike.
- SCRs: two-transistor latching model. Opto drivers: voltage-controlled switches.
- Firmware: behavioural. Charge SCR is fired on a positive half-cycle when the
  filtered battery voltage is under the set point and filtered output current
  is under the limit (cycle-by-cycle control). Lamp SCR firing angle comes from
  an integrator on the filtered square of the lamp voltage (true RMS loop).
- U7 (15.2 V) and U8 (battery NTC) are comparators with hysteresis on the real
  resistor networks. F1 is an I^2*t fuse (melts at 70 A^2*s).
- Battery: 12 V 2.5 Ah lead-acid as OCV + R0 + one RC polarisation branch. The
  RC time constant is compressed to 0.125 s so a 1 s run shows behaviour that
  takes minutes on a bike. Treat time axes as compressed.
"""
import json, math, os, subprocess, sys, tempfile
from concurrent.futures import ThreadPoolExecutor
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

# ------------------------------------------------------------------ circuit
BASE = r"""
* ---------------- stator (6 pole pairs, f = rpm/10)
Brpm rpm 0 V = {rpm}
Bph 0 ph I = v(rpm)/10
Cph ph 0 1
Rph ph 0 1e12
Bew ew {wref} V = {kw}*v(rpm)/1000*sin(2*pi*v(ph))
Lw ew w1 {lw}
Rw w1 w {rw}
Bey ey 0 V = {ky}*v(rpm)/1000*sin(2*pi*v(ph))
Ly ey y1 {ly}
Ry y1 y {ry}

* ---------------- input clamps TVS1 / TVS3 (bidirectional 130 V)
D1a t1 w DT130
D1b t1 0 DT130
D3a t3 y DT130
D3b t3 0 DT130

* ---------------- charge path: SCR1, snubber R2/C1, RS1, C2, TVS2, crowbar
Rr1 w n1 180
S1 n1 gd1 cmd1 0 SOPTO
Dg1 gd1 g1 DSIG
X1 w g1 k1 SCR
{scr1_fault}
Rsn w sn 100
Csn sn k1 100n
Vis k1 k1a 0
Rs1 k1a rail 0.01
{bridge}
C2 {c2node} c2e 2200u
Rc2 c2e 0 0.05
Dtvs2 0 {tvs2node} DT16
Vi3 rail a3 0
X3 a3 g3 0 SCR
Dz2 zc rail DZ16
R17 zc g3 470
C4 g3 0 47u
R16 g3 0 1k

* ---------------- F1 10 A, I^2 t fuse (70 A^2 s)
Vf rail f1 0
Bf f1 r I = v(f1,r)/(0.005 + 1e5*(0.5+0.5*tanh((v(fi)-70)*20)))
Bfi 0 fi I = i(Vf)*i(Vf)
Cfi fi 0 1
Rfi fi 0 1e12

* ---------------- battery and harness (J1-R = node r)
Sbat r b0 bcmd 0 SBAT
Vbc bcmd 0 {batsw}
Vib b0 b1 0
Rb0 b1 b2 {r0}
Rbp b2 b3 {rp}
Cbp b2 b3 0.125
Vocv b3 0 {ocv}

* ---------------- DC loads on the red wire
Vld r rl 0
Rbase rl 0 72
Sstop rl 0 cstop 0 SLOAD6
Vstop cstop 0 {stop}
Sind rl 0 cind 0 SLOAD7
Vind cind 0 {ind}
Shorn rl 0 chorn 0 SLOAD5
Vhorn chorn 0 {horn}
Sshort rl 0 cshort 0 SSHORT
Vshort cshort 0 {short}

* ---------------- lighting: SCR2 shunt, R7 gate feed, DZ1 backup, lamps
Rlamp y 0 {rlamp}
Vi2 0 a2 0
X2 a2 g2 y SCR
R7 0 n7 180
S2 n7 gd2 cmd2 0 SOPTO
Dg2 gd2 g2 DSIG
Dz1 g2 0 DZ56
Vi4 y a4 0
X4 a4 g4 0 SCR
R7b y n7b 180
S4 n7b gd4 cmd4 0 SOPTO
Dg4 gd4 g4 DSIG
Dz1b g4 y DZ56

* ---------------- firmware model (U2)
Bvb vbm 0 V = v(r)
Rvb vbm vbf 1k
Cvb vbf 0 10u
Bim im 0 V = {isense}
Rim im imf 1k
Cim imf 0 20u
Bsc 0 sct I = v(vbf) < 8 ? 1 : 0
Csc sct 0 1
Rsc sct 0 1e12
Bwin win 0 V = sin(2*pi*v(ph)) > 0 ? 1 : 0
Bfw fw 0 V = {fwexpr}
Bhw hw 0 V = (v(u7c) < 2.5 && v(u8c) < 2.5) ? 1 : 0
Brp rp 0 V = abs(v(r)-v(vbf))
Rrp rp rpf 1k
Crp rpf 0 5u
Bnb 0 nbl I = v(rpf) > 0.6 ? 1 : 0
Cnb nbl 0 1
Rnb nbl 0 1e12
Bv1 0 ctl1 I = v(nbl) > 0.005 && (v(ctl1) < 1 || v(vbf) > 13.8) && (v(ctl1) > 0 || v(vbf) < 13.8) ? 30*(13.8 - v(vbf)) : 0
Cv1 ctl1 0 1
Rv1 ctl1 0 1e9
Bpa pa 0 V = ((v(ph)-floor(v(ph))) < 0.5 && 2*(v(ph)-floor(v(ph))) > 1-(v(ctl1)+0.15*(13.8-v(vbf)))) ? 1 : 0
Bc1 cmd1 0 V = {cmd1expr}
* gate-off current check: more than 6 ms since U2 last commanded any gate, yet RS1 reads
* over 0.5 A for 3 ms -> an SCR is conducting by itself -> U2 fires SCR3 via Q4/R27 (40 mA)
Bgo 0 tsl I = ({gateoff}) > 0.5 ? 1 : -2e4*v(tsl)
Ctsl tsl 0 1
Rtsl tsl 0 1e12
Rif im imf2 1k
Cif imf2 0 0.5u
Bxc 0 xct I = (v(tsl) > 0.006 && v(imf2) > 0.5 && v(nbl) < 0.005) ? 1 : -1000*v(xct)
Cxc xct 0 1
Rxc xct 0 1e12
Bxl 0 xl I = v(xct) > 0.003 ? 1 : 0
Cxl xl 0 1
Rxl xl 0 1e12
Bxg 0 g3 I = (v(xl) > 0.001 && v(r) > 5) ? 0.04 : 0
* forced test window: gates held off 15 ms in every second (every few seconds on the bike)
Btw tw 0 V = (v(nbl) < 0.005 && (time - floor(time)) > 0.985) ? 0 : 1
* lamp loop
By2 y2 0 V = v(y)*v(y)
Ry2 y2 y2f 1k
Cy2 y2f 0 10u
Bct 0 ctl I = (v(ctl) < 1 || v(y2f) < 174.24) && (v(ctl) > 0 || v(y2f) > 174.24) ? 0.08*(v(y2f)-174.24) : 0
Cct ctl 0 1
Rct ctl 0 1e9
Bc2 cmd2 0 V = ((v(ph)-floor(v(ph))) >= 0.5 && 2*((v(ph)-floor(v(ph)))-0.5) > 1-v(ctl)) ? 5 : 0
Bc4 cmd4 0 V = ((v(ph)-floor(v(ph))) < 0.5 && 2*(v(ph)-floor(v(ph))) > 1-v(ctl)) ? 5 : 0

* ---------------- U7 TPS3700: 15.2 V trip, 14.8 V release (open drain)
Bu7i u7m 0 V = v(r)
Ru7i u7m u7f 10k
Cu7i u7f 0 1u
Bu7 u7o 0 V = v(u7f) > (15.2 - 0.08*v(u7c)) ? 5 : 0
Ru7 u7o u7c 1k
Cu7 u7c 0 1u
* ---------------- U8 TLV7021: NTC2 + R8 vs R10/R11 threshold
V5 v5 0 5
R8 v5 tdiv 10k
Rntc tdiv 0 {rntc}
R10 v5 thr 33k
R11 thr 0 10k
Bu8 u8o 0 V = v(tdiv) < (v(thr) + 0.01*v(u8c)) ? 5 : 0
Ru8 u8o u8c 1k
Cu8 u8c 0 1u

* ---------------- models
.subckt SCR A G K
Q1 X G K NSCR
Q2 G X A PSCR
Rgk G K 1k
.ends
.model NSCR NPN(IS=1e-10 BF=60 RB=0.02 RC=0.005 RE=0.012 CJE=2n TF=2u)
.model PSCR PNP(IS=1e-10 BF=2 RB=0.02 RC=0.005 RE=0.012 CJE=2n TF=2u)
.model DT130 D(IS=1e-12 BV=144 IBV=1m RS=0.6)
.model DT16 D(IS=1e-12 BV=17.8 IBV=1m RS=0.25)
.model DSIG D(IS=2n RS=0.5 BV=1000)
.model DZ16 D(IS=1e-14 BV=16 IBV=5m RS=5)
.model DZ56 D(IS=1e-14 BV=56 IBV=5m RS=5)
.model SOPTO SW(VT=2.5 VH=0.1 RON=5 ROFF=1e9)
.model SFUSE SW(VT=0 VH=0 RON=0.005 ROFF=1e7)
.model SBAT SW(VT=2.5 VH=0 RON=0.001 ROFF=1e8)
.model SLOAD6 SW(VT=2.5 VH=0 RON=6.9 ROFF=1e8)
.model SLOAD7 SW(VT=2.5 VH=0 RON=7.2 ROFF=1e8)
.model SLOAD5 SW(VT=2.5 VH=0 RON=4.8 ROFF=1e8)
.model SSHORT SW(VT=2.5 VH=0 RON=0.02 ROFF=1e8)
.ic v(ph)=0 v(rail)={vinit} v(r)={vinit} v(b2)={vb2} v(b3)={ocv} v(c2e)=0 v(ctl)={ctl0} v(fi)=0 v(nbl)=0 v(xct)=0 v(xl)=0 v(tsl)=0 v(ctl1)=0.6 v(sct)=0 v(g3)=0 v(u7f)={vinit} v(u7c)=0 v(u8c)=0 v(vbf)={vinit}
.options method=gear reltol=2e-3 abstol=1e-8 vntol=1e-5 itl4=100
.control
set wr_singlescale
set wr_vecnames
option numdgt=6
tran 5u {tstop} 0 5u uic
linearize
wrdata {out} v(r) v(rail) i(Vis) i(Vib) i(Vld) v(y) i(Vi2) v(w) v(k1) v(rpm) v(fi) v(u7c) v(u8c) v(ctl) i(Vi3) v(y2f) i(Vi4) v(cmd1) v(win) v(u7f) v(nbl)@@WREXTRA@@
quit
.endc
.end
"""

STATOR = dict(kw=11.0, lw="1.4m", rw=0.35, ky=11.0, ly="1.6m", ry=0.30)
OCV = 12.75


def ntc(tc):
    return 10e3 * math.exp(3950 * (1 / (tc + 273.15) - 1 / 298.15))


def vset_for(tc):
    if tc >= 50:
        return None
    if tc >= 45:
        return 13.6
    return max(14.04, min(14.85, 14.4 - 0.018 * (tc - 25)))


def pwl(points):
    return "pwl(time, %s)" % ", ".join("%g, %g" % p for p in points)


def pulse(t_on, t_off, per=None, width=None):
    # 5 V while the load is on
    if per:
        return "PULSE(0 5 %g 1u 1u %g %g)" % (t_on, width, per)
    return "PWL(0 0 %g 0 %g 5 %g 5 %g 0)" % (t_on, t_on + 1e-6, t_off, t_off + 1e-6)


OFF = "0"


def tc(id, title, kind, rpm, tstop=1.0, stop=OFF, ind=OFF, horn=OFF, lamp="on",
       temp=25, fw="normal", scr1="ok", battery="ok", short=OFF, vinit=14.3, criteria=()):
    return dict(id=id, title=title, kind=kind, rpm=rpm, tstop=tstop, stop=stop, ind=ind, horn=horn,
                lamp=lamp, temp=temp, fw=fw, scr1=scr1, battery=battery, short=short, vinit=vinit,
                criteria=criteria)


IND = pulse(0, 0, per=0.667, width=0.333)          # 1.5 Hz flasher
IND_D = "PULSE(0 5 0.2 1u 1u 0.333 0.667)"

TESTS = [
    tc("T01", "Idle, no extra load", "Load", "1400", lamp="off"),
    tc("T02", "Idle, headlamp on", "Load", "1400"),
    tc("T03", "Cruise, light load", "Load", "4000", lamp="off"),
    tc("T04", "Cruise, day riding: stop lamp and indicators", "Load", "4000", lamp="off",
       stop=pulse(0.2, 0.8), ind=IND_D),
    tc("T05", "Cruise, night: headlamp, stop lamp, indicators", "Load", "4000",
       stop=pulse(0.2, 0.8), ind=IND_D),
    tc("T06", "Full load: everything on plus horn bursts", "Load", "5000",
       stop=pulse(0.1, 0.9), ind=IND_D, horn="PULSE(0 5 0.3 1u 1u 0.25 0.5)"),
    tc("T07", "Overload at low revs: all DC loads at 2500 rpm", "Load", "2500",
       stop=pulse(0.1, 0.9), ind=IND_D, horn=pulse(0.3, 0.7)),
    tc("T08", "Redline, light load", "Load", "8000", lamp="off"),
    tc("T09", "Redline, full load", "Load", "8000",
       stop=pulse(0.1, 0.9), ind=IND_D, horn=pulse(0.4, 0.6)),
    tc("T10", "Rev sweep 1400 to 8000 to 1400 rpm, night load", "Load",
       pwl([(0, 1400), (0.8, 8000), (1.6, 1400)]), tstop=1.6, stop=pulse(0.2, 1.4)),
    tc("T11", "Pulsed load: horn chattering at 5 Hz", "Load", "3500", lamp="off",
       horn="PULSE(0 5 0.1 1u 1u 0.1 0.2)"),
    tc("T12", "Load dump: battery disconnected at 5000 rpm", "Fault", "5000",
       stop=pulse(0.1, 0.9), ind=IND_D, battery="dump"),
    tc("T13", "Headlamp bulb blown at redline", "Fault", "8000", lamp="blown"),
    tc("T14", "Output short circuit (main fuse already open)", "Fault", "4000", lamp="off",
       battery="off", short=pulse(0.2, 1.0), vinit=14.0),
    tc("T15", "Cold battery, 0 °C", "Temperature", "4000", lamp="off", temp=0),
    tc("T16", "Warm battery, 47 °C: float only", "Temperature", "4000", lamp="off", temp=47),
    tc("T17", "Hot battery 58 °C with firmware stuck firing", "Protection", "4000", lamp="off",
       temp=58, fw="stuck"),
    tc("T18", "Firmware stuck firing at 25 °C: U7 cut-out", "Protection", "6000", lamp="off", fw="stuck"),
    tc("T19", "SCR1 self-firing (gate fault): crowbar", "Protection", "6000", lamp="off", scr1="selffire"),
    tc("T20", "SCR1 failed short", "Protection", "4000", tstop=1.5, lamp="off", scr1="short"),
    tc("T21", "Battery connected backwards", "Protection", "1400", lamp="off", battery="reverse",
       vinit=0.0),
]


BRIDGE = r"""
* ---- full-wave bridge: second SCR leg (SCR4), D3 dual Schottky bottom legs, W2 clamp
D1c t1b w2 DT130
D1d t1b 0 DT130
Vdb1 0 db1 0
Db1 db1 w DSCH
Vdb2 0 db2 0
Db2 db2 w2 DSCH
Rr1b w2 n1b 180
S1b n1b gd1b cmd1b 0 SOPTO
Dg1b gd1b g1b DSIG
X1b w2 g1b k1b SCR
Visb k1b k1a 0
Rsnb w2 snb 100
Csnb snb k1a 100n
Bwin2 win2 0 V = sin(2*pi*v(ph)) < 0 ? 1 : 0
Bpa2 pa2 0 V = ((v(ph)-floor(v(ph))) >= 0.5 && 2*((v(ph)-floor(v(ph)))-0.5) > 1-(v(ctl1)+0.15*(13.8-v(vbf)))) ? 1 : 0
Bc1b cmd1b 0 V = {cmd1b}
.model DSCH D(IS=1e-6 N=1.3 RS=0.01 BV=220 IBV=1m CJO=500p)
"""
ILIM = {"C": 4.0, "B": 4.0, "F": 4.5}
RSA_T = {"C": 3.0, "B": 3.0, "F": 2.2}   # case wall to air, °C/W: the bridge needs bigger fins
BRIDGE_COLS = ["vw2", "vk1b", "ichgb", "idb1", "idb2", "cmd1b", "win2"]


def netlist(t, out, topology="C"):
    ilim = ILIM[topology]
    lamp_r = {"on": 4.05, "off": 17.0, "blown": 17.0}[t["lamp"]]
    vset = vset_for(t["temp"])
    if t["fw"] == "stuck":
        fwexpr = "1"
    elif vset is None:
        fwexpr = "0"
    else:
        fwexpr = "(v(imf) < %g && v(sct) < 0.1" % ilim + " && (v(nbl) > 0.005 ? 1 : v(vbf) < %g)) ? 1 : 0" % vset
    # firmware has no supply if the battery is reversed (D1 blocks)
    if t["battery"] == "reverse":
        fwexpr = "0"
    fwcmd1 = "(v(nbl) > 0.005 ? v(pa) : v(win))*v(fw)*v(tw)"
    cmd1 = "5*" + fwcmd1 + "*v(hw)"
    if t["scr1"] == "selffire":
        cmd1 = "5"
    gateoff = "(%s) < 0.5 ? 1 : 0" % fwcmd1
    scr1_fault = "Rsh w k1 0.01" if t["scr1"] == "short" else ""
    bridge, wref, isense, wrextra = "", "0", "i(Vis)", ""
    if topology == "F":
        fwcmd1b = "(v(nbl) > 0.005 ? v(pa2) : v(win2))*v(fw)*v(tw)"
        cmd1b = "5*" + fwcmd1b + "*v(hw)"
        gateoff = "((%s) < 0.5 && (%s) < 0.5) ? 1 : 0" % (fwcmd1, fwcmd1b)
        bridge = BRIDGE.replace("{cmd1b}", cmd1b)
        wref, isense = "w2", "i(Vis)+i(Visb)"
        wrextra = " v(w2) v(k1b) i(Visb) i(Vdb1) i(Vdb2) v(cmd1b) v(win2)"
    r0, rp = (0.01, 0.001) if t.get("stiff") else (0.06, 0.8)
    batsw = {"ok": "5", "reverse": "PWL(0 0 0.01 0 0.0101 5)", "off": "0",
             "dump": "PWL(0 5 0.4 5 0.40001 0)"}[t["battery"]]
    ocv = -OCV if t["battery"] == "reverse" else OCV
    vinit = t["vinit"]
    vb2 = vinit if t["battery"] != "reverse" else ocv
    # Rev B had C2 and TVS2 on the battery side of F1, Rev C moves them to the rail
    c2node, tvs2node = ("r", "r") if topology == "B" else ("rail", "rail")
    rpm = t["rpm"]
    if not rpm.startswith("pwl"):
        rpm = rpm
    ctl0 = 0.3
    return "* %s %s\n" % (t["id"], t["title"]) + BASE.format(
        rpm=rpm, rlamp=lamp_r, fwexpr=fwexpr, cmd1expr=cmd1, scr1_fault=scr1_fault, batsw=batsw,
        ocv=ocv, vinit=vinit, vb2=vb2, c2node=c2node, tvs2node=tvs2node, rntc="%.1f" % ntc(t["temp"]),
        stop=t["stop"], ind=t["ind"], horn=t["horn"], short=t["short"], tstop=t["tstop"], out=out,
        ctl0=ctl0, gateoff=gateoff, bridge=bridge, wref=wref, isense=isense, r0=r0, rp=rp, **STATOR).replace("@@WREXTRA@@", wrextra)


def run(t, topology="C"):
    d = tempfile.mkdtemp(prefix="rr_")
    out = os.path.join(d, "out.txt")
    cir = os.path.join(d, "tb.cir")
    open(cir, "w").write(netlist(t, out, topology))
    r = subprocess.run(["ngspice", "-b", cir], capture_output=True, text=True, timeout=1800)
    if not os.path.exists(out):
        raise RuntimeError("%s failed:\n%s" % (t["id"], (r.stdout + r.stderr)[-3000:]))
    data = np.loadtxt(out, skiprows=1)
    return d, data


COLS = ["time", "vr", "vrail", "ichg", "ibat", "iload", "vy", "i2", "vw", "vk1", "rpm", "fi", "u7", "u8",
        "ctl", "i3", "y2f", "i4", "cmd1", "win", "u7f", "nbl"]


def analyse(t, data, ilim=4.0, rsa=None):
    rsa = RSA if rsa is None else rsa
    bridge = data.shape[1] > len(COLS)
    D = {c: data[:, i] for i, c in enumerate(COLS + (BRIDGE_COLS if bridge else []))}
    if bridge:
        ia = D["ichg"].copy()
        D["ichg"] = ia + D["ichgb"]
    tt = D["time"]
    T = tt[-1]
    late = tt > T * 0.5

    def mean(x, m=late):
        return float(np.mean(x[m]))

    vr = D["vr"]
    # battery voltage seen at the battery terminal (r node minus nothing; r is J1-R)
    # lamp RMS over the last half, per-window
    def rms(x, m):
        return float(np.sqrt(np.mean(x[m] ** 2)))
    p1 = (D["vw"] - D["vk1"]) * (ia if bridge else D["ichg"])
    p1b = (D["vw2"] - D["vk1b"]) * D["ichgb"] if bridge else np.zeros_like(p1)
    pd = ((0 - D["vw"]) * D["idb1"] + (0 - D["vw2"]) * D["idb2"]) if bridge else np.zeros_like(p1)
    p2 = (0 - D["vy"]) * D["i2"] + D["vy"] * D["i4"]
    res = dict(
        vbat_mean=mean(vr), vbat_min=float(vr[tt > 0.05].min()), vbat_max=float(vr[tt > 0.05].max()),
        vrail_max=float(D["vrail"].max()), vrail_min=float(D["vrail"].min()),
        ichg_mean=mean(D["ichg"]), ichg_max_avg=0.0, iload_mean=mean(D["iload"]), ibat_mean=mean(D["ibat"]),
        vy_rms=rms(D["vy"], late), vy_pk=float(D["vy"].max()), vy_neg_pk=float(D["vy"].min()),
        p_scr1=mean(np.clip(p1, 0, None)), p_scr2=mean(np.clip(p2, 0, None)),
        fuse_blown=bool(D["fi"][-1] > 69.6), fuse_t=None, vbat_fmax=float(D["u7f"][tt > 0.05].max()),
        nobat=bool(D["nbl"].max() > 0.005), u7_trip=bool(D["u7"].max() > 2.5),
        u8_trip=bool(D["u8"].max() > 2.5), i3_max=float(D["i3"].max()),
    )
    # 20 ms moving average of charge current
    dt = tt[1] - tt[0]
    n = max(1, int(0.02 / dt))
    ma = np.convolve(D["ichg"], np.ones(n) / n, mode="same")
    res["ichg_max_avg"] = float(ma[tt > 0.05].max())
    res["lim_frac"] = float(np.mean(ma[late] >= 0.95 * ilim))
    if res["fuse_blown"]:
        res["fuse_t"] = float(tt[np.argmax(D["fi"] > 69.6)])
    # whole-run worst SCR1 power over any 100 ms window
    n100 = max(1, int(0.1 / dt))
    res["p_scr1_peak"] = float(np.convolve(np.clip(p1, 0, None), np.ones(n100) / n100, mode="valid").max())
    res["p_scr2_peak"] = float(np.convolve(np.clip(p2, 0, None), np.ones(n100) / n100, mode="valid").max())
    # junction temperature: 70 °C air, 3 °C/W junction-to-wall, 5 °C/W wall-to-air shared
    res["bridge"], res["ilim"] = bridge, ilim
    res["p_scr1b"] = mean(np.clip(p1b, 0, None))
    res["p_d3"] = mean(np.clip(pd, 0, None))
    tot = res["p_scr1"] + res["p_scr1b"] + res["p_d3"] + res["p_scr2"]
    res["p_total"] = tot
    res["tj1"] = TAIR + RJW * res["p_scr1"] + rsa * tot
    res["tj1b"] = TAIR + RJW * res["p_scr1b"] + rsa * tot
    res["tjd"] = TAIR + RJW * res["p_d3"] + rsa * tot
    res["tj2"] = TAIR + RJW * res["p_scr2"] + rsa * tot
    fired = (D["cmd1"] > 2.5) & (D["win"] > 0.5)
    win = D["win"] > 0.5
    if bridge:
        fired = fired | ((D["cmd1b"] > 2.5) & (D["win2"] > 0.5))
        win = win | (D["win2"] > 0.5)
    normal = late & ((tt - np.floor(tt)) <= 0.985)       # leave out the forced test window
    res["fire_frac"] = float(np.sum(fired & normal) / max(1, np.sum(win & normal)))
    res["ripple_pp"] = float(np.mean([np.ptp(vr[(tt >= a) & (tt < a + 0.02)]) for a in np.arange(T * 0.6, T - 0.02, 0.02)]))
    res["rail_late_min"] = float(D["vrail"][tt > T * 0.6].min())
    res["rail_late_max"] = float(D["vrail"][tt > T * 0.6].max())
    res["c2_rev_end"] = float(np.mean(D["vrail"][tt > T * 0.8]))
    # lamp RMS per 50 ms window in the late part
    w = max(1, int(0.05 / dt))
    y2 = D["vy"] ** 2
    win_rms = np.sqrt(np.convolve(y2, np.ones(w) / w, mode="valid"))
    res["vy_rms_min"] = float(win_rms[w:].min())
    res["vy_rms_max"] = float(win_rms[w:].max())
    # waveforms for the page: ~600 points, averaged over buckets
    N = 600
    edges = np.linspace(0, len(tt), N + 1).astype(int)
    def bucket(x, f=np.mean):
        return [round(float(f(x[a:b])), 3) for a, b in zip(edges[:-1], edges[1:])]
    wave = dict(t=bucket(tt), v=bucket(vr), vmax=bucket(vr, np.max), vmin=bucket(vr, np.min),
                ichg=bucket(D["ichg"]), iload=bucket(D["iload"]), ibat=bucket(D["ibat"]),
                vy=[round(float(math.sqrt(np.mean(y2[a:b]))), 3) for a, b in zip(edges[:-1], edges[1:])],
                rpm=bucket(D["rpm"]))
    # one 2-cycle zoom of raw stator/lamp waveforms in the late part
    f = float(D["rpm"][-1]) / 10
    span = 2 / f
    m = (tt > T - span) & (tt <= T)
    idx = np.where(m)[0]
    step = max(1, len(idx) // 300)
    idx = idx[::step]
    zoom = dict(t=[round(float(x - tt[idx[0]]) * 1000, 4) for x in tt[idx]],
                vw=[round(float(x), 2) for x in D["vw"][idx]], vy=[round(float(x), 2) for x in D["vy"][idx]],
                vr=[round(float(x), 3) for x in vr[idx]], ichg=[round(float(x), 3) for x in D["ichg"][idx]])
    return res, wave, zoom


RSA = 3.0      # finned case wall to air, °C/W (shared by all power devices)
RJW = 3.0      # junction to wall through tab and pad, °C/W
TAIR = 70.0    # air around the unit, °C


def verdict(t, r):
    """Return list of checks: dict(check, measured, limit, ok)."""
    ck = []

    def add(name, meas, lim, ok):
        ck.append(dict(check=name, measured=meas, limit=lim, ok=bool(ok)))

    def junctions():
        add("SCR1 junction, 70 °C air", "%.0f °C (%.1f W)" % (r["tj1"], r["p_scr1"]), "below 125 °C", r["tj1"] < 125)
        add("TR1 junction, 70 °C air", "%.0f °C (%.1f W)" % (r["tj2"], r["p_scr2"]), "below 125 °C", r["tj2"] < 125)
        if r.get("bridge"):
            add("SCR4 junction, 70 °C air", "%.0f °C (%.1f W)" % (r["tj1b"], r["p_scr1b"]), "below 125 °C", r["tj1b"] < 125)
            add("D3 junction, 70 °C air", "%.0f °C (%.1f W)" % (r["tjd"], r["p_d3"]), "below 125 °C", r["tjd"] < 125)

    vset = vset_for(t["temp"])
    i = t["id"]
    if t["kind"] in ("Load", "Temperature"):
        if i in ("T01", "T02"):
            add("Battery voltage range", "%.2f to %.2f V" % (r["vbat_min"], r["vbat_max"]), "12.2 to 14.9 V",
                12.2 <= r["vbat_min"] and r["vbat_max"] <= 14.9)
        elif i != "T10":
            dv = r["vbat_mean"] - vset
            sat = r["fire_frac"] >= 0.9
            if dv < -0.2 and (r["ichg_mean"] >= 0.9 * r["ilim"] or r["lim_frac"] >= 0.2):
                add("Average battery voltage", "%.2f V, current limit active %.0f %% of the time" % (r["vbat_mean"], 100 * r["lim_frac"]),
                    "%.2f ± 0.20 V, or at %g A limit" % (vset, r["ilim"]), True)
            elif dv < -0.2 and sat:
                add("Average battery voltage", "%.2f V, stator at full output (%.0f %% of cycles)" % (r["vbat_mean"], 100 * r["fire_frac"]),
                    "%.2f ± 0.20 V, or at full output" % vset, True)
            else:
                add("Average battery voltage", "%.2f V" % r["vbat_mean"], "%.2f ± 0.20 V" % vset, abs(dv) <= 0.2)
        add("Peak battery voltage, 10 ms filtered", "%.2f V" % r["vbat_fmax"], "below 15.2 V", r["vbat_fmax"] < 15.2)
        if i == "T07":
            add("Battery under overload", "%.2f V, %s %.2f A" % (r["vbat_mean"], "charging" if r["ibat_mean"] >= 0 else "discharging", abs(r["ibat_mean"])),
            "above 11.5 V", r["vbat_mean"] > 11.5)
        if t["lamp"] == "on" and i not in ("T02", "T07", "T10"):
            add("Headlamp voltage", "%.2f V RMS" % r["vy_rms"], "13.2 ± 0.4 V RMS", abs(r["vy_rms"] - 13.2) <= 0.4)
        elif i in ("T02", "T07"):
            add("Headlamp voltage (stator-limited)", "%.2f V RMS" % r["vy_rms"], "below 13.6 V RMS", r["vy_rms"] < 13.6)
        elif i == "T10":
            add("Headlamp voltage, worst 50 ms", "%.2f V RMS" % r["vy_rms_max"], "below 14.5 V RMS (transient)", r["vy_rms_max"] < 14.5)
        else:
            add("Tail and meter lamp voltage", "%.2f V RMS" % r["vy_rms"], "below 13.6 V RMS", r["vy_rms"] < 13.6)
        junctions()
        add("F1 intact, crowbar off", "%s, %.1f A peak in SCR3" % ("blown" if r["fuse_blown"] else "intact", r["i3_max"]),
            "intact, below 1 A", (not r["fuse_blown"]) and r["i3_max"] < 1)
    elif i == "T12":
        add("Rail peak after disconnect", "%.2f V" % r["vrail_max"], "below 20 V (parts rated 35 V+)", r["vrail_max"] < 20)
        add("No-battery mode entered", "yes" if r["nobat"] else "no", "yes", r["nobat"])
        add("Average voltage, battery off", "%.2f V" % r["vbat_mean"], "13.8 ± 0.6 V", abs(r["vbat_mean"] - 13.8) <= 0.6)
        add("Crowbar stays off", "%.1f A peak" % r["i3_max"], "below 1 A", r["i3_max"] < 1)
        add("Rail ripple, battery off", "%.1f to %.1f V" % (r["rail_late_min"], r["rail_late_max"]), "reported", True)
    elif i == "T13":
        add("Tail and meter lamp voltage", "%.2f V RMS" % r["vy_rms"], "below 13.6 V RMS", r["vy_rms"] < 13.6)
        add("Lamp wire peak", "%.1f V" % max(r["vy_pk"], -r["vy_neg_pk"]), "below 60 V", max(r["vy_pk"], -r["vy_neg_pk"]) < 60)
        junctions()
        add("F1 intact, crowbar off", "%s, %.1f A peak in SCR3" % ("blown" if r["fuse_blown"] else "intact", r["i3_max"]),
            "intact, below 1 A", (not r["fuse_blown"]) and r["i3_max"] < 1)
    elif i == "T14":
        add("Charging shut down after short", "%.2f A average" % r["ichg_mean"], "below 0.1 A", r["ichg_mean"] < 0.1)
        add("Worst 20 ms current before shutdown", "%.2f A" % r["ichg_max_avg"], "below 10 A (F1 holds)", r["ichg_max_avg"] < 10)
        add("F1", "blown" if r["fuse_blown"] else "intact", "intact", not r["fuse_blown"])
    elif i == "T17":
        add("U8 battery temperature cut-out", "tripped" if r["u8_trip"] else "did not trip", "trips", r["u8_trip"])
        add("Charge current after trip", "%.3f A" % r["ichg_mean"], "below 0.05 A", r["ichg_mean"] < 0.05)
        add("F1 intact", "blown" if r["fuse_blown"] else "intact", "intact", not r["fuse_blown"])
    elif i == "T18":
        add("U7 over-voltage cut-out", "tripped" if r["u7_trip"] else "did not trip", "trips", r["u7_trip"])
        add("Peak battery voltage, 10 ms filtered", "%.2f V" % r["vbat_fmax"], "below 15.5 V", r["vbat_fmax"] < 15.5)
        add("Crowbar stays off", "%.1f A peak" % r["i3_max"], "below 1 A", r["i3_max"] < 1)
    elif i == "T19":
        add("Crowbar fired", "%.0f A peak" % r["i3_max"], "fires", r["i3_max"] > 5)
        add("F1 blown, battery isolated", "at %.0f ms" % (r["fuse_t"] * 1000) if r["fuse_blown"] else "intact",
            "blows", r["fuse_blown"])
        add("Peak battery voltage, 10 ms filtered", "%.2f V" % r["vbat_fmax"], "below 17.0 V", r["vbat_fmax"] < 17.0)
    elif i == "T20":
        add("F1 blown, battery isolated", "at %.0f ms" % (r["fuse_t"] * 1000) if r["fuse_blown"] else "intact",
            "blows", r["fuse_blown"])
        add("Peak battery voltage, 10 ms filtered", "%.2f V" % r["vbat_fmax"], "below 15.2 V", r["vbat_fmax"] < 15.2)
    elif i == "T21":
        add("F1 blown", "at %.1f ms" % (r["fuse_t"] * 1000) if r["fuse_blown"] else "intact", "blows", r["fuse_blown"])
        add("C2 reverse voltage after F1 opens", "%.2f V" % r["c2_rev_end"], "above −1 V", r["c2_rev_end"] > -1)
    return ck


CAP_RPM = [1000, 1400, 2000, 3000, 4000, 5000, 6000, 7000, 8000]


def capacity(topology):
    """Maximum charge current into a stiff 13.5 V battery, SCRs fired every half-cycle."""
    out = []
    for rpm in CAP_RPM:
        t = tc("CAP", "capacity %d rpm" % rpm, "Capacity", str(rpm), tstop=0.25, lamp="off", fw="stuck", vinit=13.5)
        t["stiff"] = True
        d, data = run(t, topology)
        r, _, _ = analyse(t, data, ILIM[topology], RSA_T[topology])
        subprocess.run(["rm", "-r", "--", d])
        p = r["p_scr1"] + r["p_scr1b"] + r["p_d3"]
        out.append(dict(rpm=rpm, amps=round(r["ichg_mean"], 3), loss=round(p, 3)))
        print("  %s %5d rpm  %.2f A  rectifier loss %.2f W" % (topology, rpm, r["ichg_mean"], p), flush=True)
    return out


def main():
    args = sys.argv[1:]
    topology = "F" if "--bridge" in args else "C"
    only = [a for a in args if not a.startswith("--")]
    tests = [t for t in TESTS if not only or t["id"] in only]
    results = []

    def job(t):
        d, data = run(t, topology)
        r, wave, zoom = analyse(t, data, ILIM[topology], RSA_T[topology])
        ck = verdict(t, r)
        print("%s %-55s %s" % (t["id"], t["title"][:55], "PASS" if all(c["ok"] for c in ck) else "FAIL"), flush=True)
        for c in ck:
            print("      %-42s %-22s %-28s %s" % (c["check"], c["measured"], c["limit"], "ok" if c["ok"] else "FAIL"))
        subprocess.run(["rm", "-r", "--", d])
        return dict(id=t["id"], title=t["title"], kind=t["kind"], checks=ck, metrics=r, wave=wave, zoom=zoom,
                    temp=t["temp"])

    with ThreadPoolExecutor(max_workers=4) as ex:
        results = list(ex.map(job, tests))
    # Rev B comparison for the reverse-battery case
    extra = []
    if topology == "C" and (not only or "T21" in only):
        t = [x for x in TESTS if x["id"] == "T21"][0]
        d, data = run(t, topology="B")
        r, _, _ = analyse(t, data)
        subprocess.run(["rm", "-r", "--", d])
        extra.append(dict(id="T21-revB", metrics=r))
        print("T21 on Rev B layout: C2 held at %.2f V, fuse %s" % (r["vbat_min"], r["fuse_blown"]))
    name = "results_bridge.json" if topology == "F" else "results.json"
    path = os.path.join(HERE, name if not only else "results_partial.json")
    cap = capacity(topology) if not only else []
    json.dump(dict(stator=STATOR, ocv=OCV, topology=topology, ilim=ILIM[topology], rsa=RSA_T[topology], tests=results, extra=extra,
                   capacity=cap), open(path, "w"), separators=(",", ":"))
    print("wrote", path)


if __name__ == "__main__":
    main()
