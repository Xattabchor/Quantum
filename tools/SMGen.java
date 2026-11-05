import java.io.*;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.*;

/**
 * CLDC "StackMap" injector for ECJ -1.3 compiled J2ME classes.
 *
 * Type-level abstract interpretation of each method; entry per branch target
 * and exception handler. CLDC preverify type tags:
 *   0 Top, 1 Integer, 2 Float, 3 Long, 4 Double, 5 Null,
 *   6 UninitializedThis, 7 Object+cpidx, 8 Uninitialized+offset
 *
 * CLI:
 *   SMGen inject <classesDir> <outDir> <apiJars>
 *   SMGen compare <referenceJar> <dir>
 */
public final class SMGen {

    static final int TOP=0, INTEGER=1, FLOAT=2, LONG=3, DOUBLE=4, NULL=5, UNINIT_THIS=6, OBJECT=7, UNINIT=8;

    static final class T {
        final int tag; final String name; final int newPc; // UNINIT: newPc = offset of NEW instruction
        T(int tag){this.tag=tag; name=null;newPc=-1;}
        T(String n){tag=OBJECT;name=n;newPc=-1;}
        T(String n,int pc){tag=UNINIT;name=n;newPc=pc;}
        public boolean equals(Object o){T t=(T)o;if(tag!=t.tag)return false; if(tag==UNINIT)return name.equals(t.name)&&newPc==t.newPc; return name==null?t.name==null:name.equals(t.name);}
        public int hashCode(){return tag*31+(name==null?0:name.hashCode())+newPc;}
        public String toString(){return tag==OBJECT?name:("#"+tag+(newPc>=0?"@"+newPc:""));}
    }
    static final T T_TOP=new T(TOP), T_INT=new T(INTEGER), T_FLOAT=new T(FLOAT),
            T_LONG=new T(LONG), T_DBL=new T(DOUBLE), T_UNINIT_THIS=new T(UNINIT_THIS), T_NULL=new T(NULL), T_CLASS=new T("java/lang/Class"), T_STRING=new T("java/lang/String");

    // ================= class parsing =================
    static final class Cp {
        byte[] kind; Object[] obj; int count;
        String utf8(int i){return (String)obj[i];}
        String className(int i){return utf8(((int[])obj[i])[0]);}
        String memberDesc(int memberIdx){int nat=((int[])obj[memberIdx])[1];return utf8(((int[])obj[nat])[1]);}
        String memberName(int memberIdx){int nat=((int[])obj[memberIdx])[1];return utf8(((int[])obj[nat])[0]);}
        private void grow(){if(count>=kind.length){int n=kind.length*2+16;byte[] k=new byte[n];System.arraycopy(kind,0,k,0,kind.length);kind=k;Object[] o=new Object[n];System.arraycopy(obj,0,o,0,obj.length);obj=o;}}
        int addUtf8(String s){for(int i=1;i<count;i++)if(kind[i]==1&&utf8(i).equals(s))return i;grow();kind[count]=1;obj[count]=s;return count++;}
        int addClass(String internal){for(int i=1;i<count;i++)if(kind[i]==7&&className(i).equals(internal))return i;int u=addUtf8(internal);grow();kind[count]=7;obj[count]=new int[]{u};return count++;}
    }

    static final class Meth {
        String name, desc; int access, maxStack, maxLocals;
        byte[] code; int[][] handlers;
        int absStart;      // Code attribute_info start in file bytes
        int contentOff;    // start of Code content (max_stack)
        int codeOff;
        int attrCntOff;    // attributes_count u2 offset inside Code
        Map frames;
    }

    static final class Frame {
        T[] locals; T[] stack; int sp;
        Frame copy(){Frame f=new Frame();f.locals=locals.clone();f.stack=stack.clone();f.sp=sp;return f;}
    }

    final URLClassLoader loader;
    final HashMap superCache=new HashMap();
    static int injectedClasses=0, injectedMethods=0;

    SMGen(URLClassLoader l){loader=l;}

    static int u1(byte[] c,int i){return c[i]&0xFF;}
    static int u2(byte[] c,int i){return ((c[i]&0xFF)<<8)|(c[i+1]&0xFF);}
    static int s2(byte[] c,int i){return (short)(((c[i]&0xFF)<<8)|(c[i+1]&0xFF));}
    static int s4(byte[] c,int i){return ((c[i]&0xFF)<<24)|((c[i+1]&0xFF)<<16)|((c[i+2]&0xFF)<<8)|(c[i+3]&0xFF);}

    T merge(T a,T b){
        if(a.equals(b))return a;
        if(a.tag==TOP||b.tag==TOP){
            T o=a.tag==TOP?b:a;
            // lift-from-TOP only for references & raw wide heads (KVM-safe: NPC long pairs after zeroed frames);
            // numerics absorb to TOP forever, otherwise int<->TOP<->wide ping-pong diverges
            return (o.tag==OBJECT||o.tag==LONG||o.tag==DOUBLE)?o:T_TOP;
        }
        if(a.tag==NULL)return b.tag==OBJECT?b:T_TOP;
        if(b.tag==NULL)return a.tag==OBJECT?a:T_TOP;
        if(a.tag==OBJECT&&b.tag==OBJECT)return new T(commonSuper(a.name,b.name));
        return T_TOP;
    }

    String commonSuper(String a,String b){
        if(a.equals(b))return a;
        if("java/lang/Object".equals(a)||"java/lang/Object".equals(b))return "java/lang/Object";
        if(a.charAt(0)=='['||b.charAt(0)=='[')return "java/lang/Object";
        String key=a+"|"+b;
        String c=(String)superCache.get(key);
        if(c!=null)return c;
        Class ca=loadCls(a), cb=loadCls(b);
        if(ca==null||cb==null){c="java/lang/Object";}
        else{
            HashSet chain=new HashSet();
            for(Class s=ca;s!=null;s=s.getSuperclass())chain.add(s.getName());
            c=null;
            for(Class s=cb;s!=null;s=s.getSuperclass())if(chain.contains(s.getName())){c=s.getName().replace('.','/');break;}
            if(c==null)c="java/lang/Object";
        }
        superCache.put(key,c);
        return c;
    }

    Class loadCls(String internal){
        try{return Class.forName(internal.replace('/','.'),false,loader);}catch(Throwable t){return null;}
    }

    T primType(String d){
        switch(d.charAt(0)){
            case 'B': case 'C': case 'I': case 'S': case 'Z': return T_INT;
            case 'F': return T_FLOAT; case 'J': return T_LONG; case 'D': return T_DBL;
            case '[': return new T(d);
            default: return new T(d.substring(1,d.length()-1));
        }
    }
    void params(String d,List out){
        int i=1;
        while(d.charAt(i)!=')'){
            int s=i;
            while(d.charAt(i)=='[')i++;
            if(d.charAt(i)=='L')while(d.charAt(i)!=';')i++;
            out.add(primType(d.substring(s,++i)));
        }
    }

    T pop(Frame f){return f.stack[--f.sp];}
    void push(Frame f,T t){f.stack[f.sp++]=t;}
    // value-level helpers: a category-2 value is TWO cells [wide, TOP]; popValue/popValue push/pop a *value*
    T popValue(Frame f){
        T top=pop(f);
        if(top.tag==TOP && f.sp>0 && wide(f.stack[f.sp-1])){ f.sp--; return f.stack[f.sp]; }
        return top;
    }
    void pushValue(Frame f,T v){ push(f,v); if(wide(v)) push(f,T_TOP); }
    void popVal(Frame f){ // pops one value: cat1 single cell, cat2 two cells (TOP sentinel on top)
        popValT(f);
    }
    T popValT(Frame f){
        T t=pop(f);
        if(t.tag==LONG||t.tag==DOUBLE){ f.sp--; }
        else if(t.tag==TOP && f.sp>0){ T u=f.stack[f.sp-1]; if(u.tag==LONG||u.tag==DOUBLE) f.sp--; }
        return t;
    }
    void popWide(Frame f){f.sp-=2;}

    static boolean wide(T t){return t.tag==LONG||t.tag==DOUBLE;}
    // category-2 pairs must merge atomically: (wide,TOP) vs (TOP,TOP)=keep wide pair; vs anything else=(TOP,TOP)
    void mergeLocals(Frame dst,Frame src,boolean[] changed){
        for(int i=0;i<dst.locals.length;i++){
            boolean isPair = i+1<dst.locals.length && (wide(dst.locals[i])||wide(src.locals[i])
                || (i>0&&(wide(dst.locals[i-1])||wide(src.locals[i-1]))));
            T m=merge(dst.locals[i],src.locals[i]);
            // wide-slot poison: a LONG/DOUBLE slot merging with a NON-wide type decays to TOP,
            // never resurrects to any value (prevents wide<->TOP flip-flop divergence)
            if((wide(dst.locals[i])||wide(src.locals[i]))&&!(m.tag==LONG||m.tag==DOUBLE))m=T_TOP;
            if(!m.equals(dst.locals[i])){dst.locals[i]=m;changed[0]=true;}
        }
    }
    void mergeStacks(Frame dst,Frame src,boolean[] changed){
        int n=Math.max(dst.sp,src.sp);
        for(int i=0;i<n;i++){
            T m=i<Math.min(dst.sp,src.sp)?merge(dst.stack[i],src.stack[i]):(i<src.sp?src.stack[i]:dst.stack[i]);
            if(i>=dst.sp||!m.equals(dst.stack[i])){dst.stack[i]=m;changed[0]=true;}
        }
        if(dst.sp!=src.sp){dst.sp=src.sp;changed[0]=true;}
    }

    Map analyze(String thisName,Meth m,Cp cp){
        byte[] code=m.code;
        int len=code.length,maxLocals=m.maxLocals,maxStack=Math.max(m.maxStack+8,16);
        Frame[] states=new Frame[len];
        TreeSet targets=new TreeSet();

        Frame entry=new Frame();
        entry.locals=new T[maxLocals];Arrays.fill(entry.locals,T_TOP);
        entry.stack=new T[maxStack];entry.sp=0;
        int li=0;
        if((m.access&0x0008)==0){entry.locals[0]="<init>".equals(m.name)?T_UNINIT_THIS:new T(thisName);li=1;}
        List ps=new ArrayList();params(m.desc,ps);
        for(int i=0;i<ps.size();i++){T t=(T)ps.get(i);entry.locals[li++]=t;if(t.tag==LONG||t.tag==DOUBLE)entry.locals[li++]=T_TOP;}

        ArrayDeque q=new ArrayDeque();
        states[0]=entry;q.add(new Integer(0));
        int guard=0;

        while(!q.isEmpty()){
                        if(++guard>3000000)throw new RuntimeException("analyze unbounded "+m.name+m.desc);
int pc=((Integer)q.poll()).intValue();
            Frame f=states[pc];
            if(f==null)continue;
            f=f.copy();
            int op=u1(code,pc);
            try {
            int next=pc+1;
            boolean end=false;
            ArrayList succ=new ArrayList(4);

            switch(op){
                case 0x00: break;
                case 0x01: push(f,T_NULL); break;
                case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: push(f,T_INT); break;
                case 0x09: case 0x0A: push(f,T_LONG); push(f,T_TOP); break;
                case 0x0B: case 0x0C: case 0x0D: push(f,T_FLOAT); break;
                case 0x0E: case 0x0F: push(f,T_DBL); push(f,T_TOP); break;
                case 0x10: push(f,T_INT); next=pc+2; break;
                case 0x11: push(f,T_INT); next=pc+3; break;
                case 0x12: push(f,ldc(cp,u1(code,pc+1))); next=pc+2; break;
                case 0x13: push(f,ldc(cp,u2(code,pc+1))); next=pc+3; break;
                case 0x14: { T t=ldc(cp,u2(code,pc+1)); push(f,t); if(t.tag==LONG||t.tag==DOUBLE)push(f,T_TOP); next=pc+3; break; } // ldc2_w
                case 0x15: case 0x17: case 0x19: push(f,f.locals[u1(code,pc+1)]); next=pc+2; break;
                case 0x16: push(f,f.locals[u1(code,pc+1)]); push(f,T_TOP); next=pc+2; break;
                case 0x18: push(f,f.locals[u1(code,pc+1)]); push(f,T_TOP); next=pc+2; break;
                case 0x1A: case 0x1B: case 0x1C: case 0x1D: push(f,f.locals[op-0x1A]); break;
                case 0x22: case 0x23: case 0x24: case 0x25: push(f,f.locals[op-0x22]); break;
                case 0x2A: case 0x2B: case 0x2C: case 0x2D: push(f,f.locals[op-0x2A]); break;
                case 0x1E: case 0x1F: case 0x20: case 0x21: push(f,f.locals[op-0x1E]); push(f,T_TOP); break;
                case 0x26: case 0x27: case 0x28: case 0x29: push(f,f.locals[op-0x26]); push(f,T_TOP); break;
                case 0x2E: popVal(f); popVal(f); push(f,T_INT); break;
                case 0x30: popVal(f); popVal(f); push(f,T_FLOAT); break;
                case 0x2F: pop(f); pop(f); push(f,T_LONG); push(f,T_TOP); break;
                case 0x31: pop(f); pop(f); push(f,T_DBL); push(f,T_TOP); break;
                case 0x32: { pop(f); T arr=pop(f); push(f,arrayElem(arr)); break; } // aaload
                case 0x33: case 0x34: case 0x35: popVal(f); popVal(f); push(f,T_INT); break;
                case 0x36: case 0x38: case 0x3A: { T t=pop(f); f.locals[u1(code,pc+1)]=t; next=pc+2; break; }
                case 0x37: { popWide(f); int s=u1(code,pc+1); f.locals[s]=T_LONG; f.locals[s+1]=T_TOP; next=pc+2; break; }
                case 0x39: { popWide(f); int s=u1(code,pc+1); f.locals[s]=T_DBL; f.locals[s+1]=T_TOP; next=pc+2; break; }
                case 0x3B: case 0x3C: case 0x3D: case 0x3E: f.locals[op-0x3B]=pop(f); break;
                case 0x43: case 0x44: case 0x45: case 0x46: f.locals[op-0x43]=pop(f); break;
                case 0x4B: case 0x4C: case 0x4D: case 0x4E: f.locals[op-0x4B]=pop(f); break;
                case 0x3F: case 0x40: case 0x41: case 0x42: { popWide(f); int s=op-0x3F; f.locals[s]=T_LONG; f.locals[s+1]=T_TOP; break; }
                case 0x47: case 0x48: case 0x49: case 0x4A: { popWide(f); int s=op-0x47; f.locals[s]=T_DBL; f.locals[s+1]=T_TOP; break; }
                case 0x4F: case 0x50: case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56:
                    popVal(f); popVal(f); popVal(f); break;
                case 0x57: popVal(f); break;
                case 0x58: { T v=popValue(f); if(!wide(v)) popValue(f); break; }
                case 0x59: { T a=pop(f); push(f,a); push(f,a); break; }
                case 0x5A: { T a=pop(f); T b=pop(f); push(f,a); push(f,b); push(f,a); break; }
                case 0x5B: { T v1=popValue(f); T v2=popValue(f);
                    if(wide(v2)){ pushValue(f,v1); pushValue(f,v2); pushValue(f,v1); }
                    else { T v3=popValue(f); pushValue(f,v1); pushValue(f,v3); pushValue(f,v2); pushValue(f,v1); } break; }
                case 0x5C: { T v1=popValue(f);
                    if(wide(v1)){ pushValue(f,v1); pushValue(f,v1); }
                    else { T v2=popValue(f); pushValue(f,v2); pushValue(f,v1); pushValue(f,v2); pushValue(f,v1); } break; }
                case 0x5D: { T v1=popValue(f); T v2=popValue(f);
                    if(wide(v1)){ pushValue(f,v1); pushValue(f,v2); pushValue(f,v1); }
                    else { T v3=popValue(f); pushValue(f,v2); pushValue(f,v1); pushValue(f,v3); pushValue(f,v2); pushValue(f,v1); } break; }
                case 0x5E: { T v1=popValue(f); T v2=popValue(f);
                    if(wide(v1)){ T v3=popValue(f); pushValue(f,v1); pushValue(f,v3); pushValue(f,v2); pushValue(f,v1); }
                    else { T v3=popValue(f);
                        if(wide(v3)){ pushValue(f,v2); pushValue(f,v1); pushValue(f,v3); pushValue(f,v2); pushValue(f,v1); }
                        else { T v4=popValue(f); pushValue(f,v2); pushValue(f,v1); pushValue(f,v4); pushValue(f,v3); pushValue(f,v2); pushValue(f,v1); } } break; }
                case 0x5F: { T a=pop(f); T b=pop(f); push(f,a); push(f,b); break; }
                case 0x60: case 0x61: case 0x62: case 0x63:
                case 0x64: case 0x65: case 0x66: case 0x67:
                case 0x68: case 0x69: case 0x6A: case 0x6B:
                case 0x6C: case 0x6D: case 0x6E: case 0x6F:
                case 0x70: case 0x71: case 0x72: case 0x73: { // add/sub/mul/div/rem, rows i,l,f,d
                    int grp=(op-0x60)&3;
                    if(grp==0){ pop(f); pop(f); push(f,T_INT); }
                    else if(grp==1){ popWide(f); popWide(f); push(f,T_LONG); push(f,T_TOP); }
                    else if(grp==2){ pop(f); pop(f); push(f,T_FLOAT); }
                    else { popWide(f); popWide(f); push(f,T_DBL); push(f,T_TOP); }
                    break; }
                case 0x74: pop(f); push(f,T_INT); break;
                case 0x75: popWide(f); push(f,T_LONG); push(f,T_TOP); break;
                case 0x76: pop(f); push(f,T_FLOAT); break;
                case 0x77: popWide(f); push(f,T_DBL); push(f,T_TOP); break;
                case 0x78: case 0x7A: case 0x7C: pop(f); pop(f); push(f,T_INT); break;   // i shl/shr/ushr
                case 0x79: case 0x7B: case 0x7D: { pop(f); popWide(f); push(f,T_LONG); push(f,T_TOP); break; } // l shl/shr/ushr (int count)
                case 0x7E: case 0x80: case 0x82: pop(f); pop(f); push(f,T_INT); break;   // iand/ior/ixor
                case 0x7F: case 0x81: case 0x83: popWide(f); popWide(f); push(f,T_LONG); push(f,T_TOP); break; // land/lor/lxor
                case 0x84: next=pc+3; break;
                case 0x85: popVal(f); push(f,T_LONG); push(f,T_TOP); break;
                case 0x86: popVal(f); push(f,T_FLOAT); break;
                case 0x87: popVal(f); push(f,T_DBL); push(f,T_TOP); break;
                case 0x88: popWide(f); push(f,T_INT); break;
                case 0x89: popWide(f); push(f,T_FLOAT); break;
                case 0x8A: popWide(f); push(f,T_DBL); push(f,T_TOP); break;
                case 0x8B: popVal(f); push(f,T_INT); break;
                case 0x8C: popVal(f); push(f,T_LONG); push(f,T_TOP); break;
                case 0x8D: popVal(f); push(f,T_DBL); push(f,T_TOP); break;
                case 0x8E: popWide(f); push(f,T_INT); break;
                case 0x8F: popWide(f); push(f,T_LONG); push(f,T_TOP); break;
                case 0x90: popWide(f); push(f,T_FLOAT); break;
                case 0x91: case 0x92: case 0x93: popVal(f); push(f,T_INT); break;
                case 0x94: popWide(f); popWide(f); push(f,T_INT); break;
                case 0x95: case 0x96: popVal(f); popVal(f); push(f,T_INT); break;
                case 0x97: case 0x98: popWide(f); popWide(f); push(f,T_INT); break;
                case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
                case 0xC6: case 0xC7: { popVal(f); int t=pc+s2(code,pc+1); targets.add(new Integer(t)); succ.add(new Integer(t)); next=pc+3; break; }
                case 0x9F: case 0xA0: case 0xA1: case 0xA2:
                case 0xA3: case 0xA4: case 0xA5: case 0xA6: { popVal(f); popVal(f); int t=pc+s2(code,pc+1); targets.add(new Integer(t)); succ.add(new Integer(t)); next=pc+3; break; }
                case 0xA7: { int t=pc+s2(code,pc+1); targets.add(new Integer(t)); succ.add(new Integer(t)); end=true; next=pc+3; break; }
                case 0xA8: throw new RuntimeException("jsr remains after -inlineJSR @"+pc);
                case 0xA9: throw new RuntimeException("ret remains after -inlineJSR @"+pc);
                case 0xAA: {
                    int pad=(4-((pc+1)&3))&3;int j=pc+1+pad;
                    int def=pc+s4(code,j);int low=s4(code,j+4);int high=s4(code,j+8);
                    popVal(f);
                    targets.add(new Integer(def));succ.add(new Integer(def));
                    for(int k=low;k<=high;k++){ int t=pc+s4(code,j+12+4*(k-low)); targets.add(new Integer(t)); succ.add(new Integer(t)); }
                    end=true;next=j+12+4*(high-low+1);break; }
                case 0xAB: {
                    int pad=(4-((pc+1)&3))&3;int j=pc+1+pad;
                    int def=pc+s4(code,j);int np=s4(code,j+4);
                    popVal(f);
                    targets.add(new Integer(def));succ.add(new Integer(def));
                    for(int k=0;k<np;k++){ int t=pc+s4(code,j+8+8*k+4); targets.add(new Integer(t)); succ.add(new Integer(t)); }
                    end=true;next=j+8+8*np;break; }
                case 0xAC: case 0xAD: popVal(f); end=true; break;
                case 0xAE: popVal(f); end=true; break;
                case 0xAF: popWide(f); end=true; break;
                case 0xB0: popVal(f); end=true; break;
                case 0xB1: end=true; break;
                case 0xBF: popVal(f); end=true; break;
                case 0xB2: { String d=cp.memberDesc(u2(code,pc+1)); push(f,primType(d)); if(d.charAt(0)=='J'||d.charAt(0)=='D')push(f,T_TOP); next=pc+3; break; }
                case 0xB3: { String d=cp.memberDesc(u2(code,pc+1)); if(d.charAt(0)=='J'||d.charAt(0)=='D')popWide(f); else pop(f); next=pc+3; break; }
                case 0xB4: { String d=cp.memberDesc(u2(code,pc+1)); popVal(f); push(f,primType(d)); if(d.charAt(0)=='J'||d.charAt(0)=='D')push(f,T_TOP); next=pc+3; break; }
                case 0xB5: { String d=cp.memberDesc(u2(code,pc+1)); if(d.charAt(0)=='J'||d.charAt(0)=='D')popWide(f); else pop(f); pop(f); next=pc+3; break; }
                case 0xB6: case 0xB8: {
                    String d=cp.memberDesc(u2(code,pc+1));
                    List args=new ArrayList(); params(d,args);
                    if(op!=0xB8)popVal(f);
                    for(int i=args.size()-1;i>=0;i--)popVal(f);
                    String r=d.substring(d.indexOf(')')+1);
                    if(r.charAt(0)!='V'){ push(f,primType(r)); if(r.charAt(0)=='J'||r.charAt(0)=='D')push(f,T_TOP); }
                    next=pc+3; break; }
                case 0xB9: {
                    String d=cp.memberDesc(u2(code,pc+1));
                    List args=new ArrayList(); params(d,args);
                    popVal(f);
                    for(int i=args.size()-1;i>=0;i--)popVal(f);
                    String r=d.substring(d.indexOf(')')+1);
                    if(r.charAt(0)!='V'){ push(f,primType(r)); if(r.charAt(0)=='J'||r.charAt(0)=='D')push(f,T_TOP); }
                    next=pc+5; break; }
                case 0xB7: { // invokespecial: <init>/private/super — receiver uninit handling
                    int mi=u2(code,pc+1);
                    String d=cp.memberDesc(mi);
                    String mn=cp.memberName(mi);
                    List args=new ArrayList(); params(d,args);
                    for(int i=args.size()-1;i>=0;i--)popVal(f);
                    T recv=popValT(f);
                    if(mn.equals("<init>")){
                        T toType=null; int markPc=-1;
                        if(recv.tag==UNINIT){ toType=new T(recv.name); markPc=recv.newPc; }
                        else if(recv.tag==UNINIT_THIS){ toType=new T(thisName); }
                        if(toType!=null){
                            for(int i2=0;i2<f.sp;i2++){
                                T t=f.stack[i2];
                                if(t.tag==UNINIT_THIS||(markPc>=0&&t.tag==UNINIT&&t.newPc==markPc&&recv.name.equals(t.name))) f.stack[i2]=toType;
                            }
                            for(int i2=0;i2<f.locals.length;i2++){
                                T t=f.locals[i2];
                                if(t!=null&&(t.tag==UNINIT_THIS||(markPc>=0&&t.tag==UNINIT&&t.newPc==markPc&&recv.name.equals(t.name)))) f.locals[i2]=toType;
                            }
                        }
                    }
                    String r=d.substring(d.indexOf(')')+1);
                    if(r.charAt(0)!='V'){ push(f,primType(r)); if(r.charAt(0)=='J'||r.charAt(0)=='D')push(f,T_TOP); }
                    next=pc+3; break; }
                case 0xBB: push(f,new T(cp.className(u2(code,pc+1)),pc)); next=pc+3; break;
                case 0xBC: { popVal(f); push(f,new T("["+PRIM[code[pc+1]&0xF])); next=pc+2; break; }
                case 0xBD: { popVal(f); push(f,new T("[L"+cp.className(u2(code,pc+1))+";")); next=pc+3; break; }
                case 0xC5: { int dim=code[pc+3]&0xFF; for(int i=0;i<dim;i++)popVal(f); push(f,new T(cp.className(u2(code,pc+1)))); next=pc+4; break; }
                case 0xBE: popVal(f); push(f,T_INT); break;
                case 0xC0: { popVal(f); push(f,new T(cp.className(u2(code,pc+1)))); next=pc+3; break; }
                case 0xC1: { popVal(f); push(f,T_INT); next=pc+3; break; }
                case 0xC2: case 0xC3: popVal(f); break;
                case 0xC4: {
                    int wop=u1(code,pc+1);
                    if(wop==0x84)next=pc+6;
                    else if(wop>=0x15&&wop<=0x19){ push(f,f.locals[u2(code,pc+2)]); if(wop==0x16||wop==0x18)push(f,T_TOP); next=pc+4; }
                    else if(wop>=0x36&&wop<=0x3A){ int s=u2(code,pc+2);
                        if(wop==0x37){ popWide(f); f.locals[s]=T_LONG; f.locals[s+1]=T_TOP; }
                        else if(wop==0x39){ popWide(f); f.locals[s]=T_DBL; f.locals[s+1]=T_TOP; }
                        else { T t=pop(f); f.locals[s]=t; }
                        next=pc+4; }
                    else throw new RuntimeException("wide 0x"+Integer.toHexString(wop));
                    break; }
                default:
                    throw new RuntimeException("unhandled 0x"+Integer.toHexString(op)+" @"+pc);
            }

            //handlers merged after
            if(m.handlers!=null&&m.handlers.length>0){
                for(int i=0;i<m.handlers.length;i++){
                    int s=m.handlers[i][0],e=m.handlers[i][1],h=m.handlers[i][2],ci=m.handlers[i][3];
                    if(pc>=s&&pc<e){
                        Frame want=f.copy();
                        want.sp=0;
                        push(want, ci==0?new T("java/lang/Throwable"):new T(cp.className(ci)));
                        boolean[] ch={false};
                        if(states[h]==null){
                            states[h]=want;
                            if(!targets.contains(new Integer(h))){targets.add(new Integer(h));}
                            q.add(new Integer(h));
                        }else{
                            mergeLocals(states[h],want,ch);
                            mergeStacks(states[h],want,ch);
                            if(ch[0])q.add(new Integer(h));
                        }
                    }
                }
            }

            if(!end)succ.add(new Integer(next));
            for(int i=0;i<succ.size();i++){
                int t=((Integer)succ.get(i)).intValue();
                if(t<0||t>=len)throw new RuntimeException("bad target "+t);
                if(states[t]==null){
                    states[t]=f.copy();
                    q.add(new Integer(t));
                }else{
                    boolean[] ch={false};
                    mergeLocals(states[t],f,ch);
                    mergeStacks(states[t],f,ch);
                    if(ch[0])q.add(new Integer(t));
                }
            }
            } catch(RuntimeException rx) {
                StringBuffer dump=new StringBuffer();
                dump.append("@pc=").append(pc).append(" op=0x").append(Integer.toHexString(op)).append(" sp=").append(f.sp);
                throw new RuntimeException(m.name+m.desc+" "+dump+" : "+rx.getMessage());
            }
        }

        if(m.handlers!=null&&m.handlers.length>0){ // CLDC rule: handler locals = union over whole region
            boolean again=true;int loops=0;
            while(again&&loops++<100){
                again=false;
                for(int i=0;i<m.handlers.length;i++){
                    int s=m.handlers[i][0],e=m.handlers[i][1],h=m.handlers[i][2];
                    Frame hf=states[h];if(hf==null)continue;
                    for(int pc=s;pc<e;pc++){
                        Frame f=states[pc];if(f==null)continue;
                        boolean[] ch={false};
                        mergeLocals(hf,f,ch);
                        if(ch[0])again=true;
                    }
                }
            }
        }

        TreeMap out=new TreeMap();
        for(Iterator it=targets.iterator();it.hasNext();){
            int pc=((Integer)it.next()).intValue();
            if(states[pc]!=null)out.put(new Integer(pc),states[pc]);
        }
        return out;
    }

    T ldc(Cp cp,int idx){
        switch(cp.kind[idx]){
            case 3: return T_INT;
            case 4: return T_FLOAT;
            case 5: return T_LONG;
            case 6: return T_DBL;
            case 7: return T_CLASS;
            case 8: return T_STRING;
            default: return T_TOP;
        }
    }

    T arrayElem(T arr){
        if(arr.tag!=OBJECT)return T_TOP;
        String n=arr.name;
        if(!n.startsWith("["))return T_TOP;
        String e=n.substring(1);
        if(e.length()==1){
            char c=e.charAt(0);
            if(c=='B'||c=='C'||c=='S'||c=='Z'||c=='I')return T_INT;
            if(c=='F')return T_FLOAT; if(c=='J')return T_LONG; if(c=='D')return T_DBL;
        }
        if(e.startsWith("L"))return new T(e.substring(1,e.length()-1));
        return new T(e);
    }

    static final String[] PRIM={null,null,null,null,"Z","C","F","D","B","S","I","J"};

    // ================= emit =================
    byte[] emitStackMapBody(Meth m,Cp cp){
        Map frames=m.frames;
        if(frames==null||frames.isEmpty())return null;
        ByteArrayOutputStream bos=new ByteArrayOutputStream();
        w2o(bos,frames.size());
        for(Iterator it=frames.entrySet().iterator();it.hasNext();){
            Map.Entry e=(Map.Entry)it.next();
            Frame f=(Frame)e.getValue();
            w2o(bos,((Integer)e.getKey()).intValue());
            int lc=m.maxLocals;
            w2o(bos,lc);
            for(int i=0;i<lc;i++)writeType(bos,f.locals[i],cp);
            int stkn=0; for(int i=0;i<f.sp;i++){T t=f.stack[i]; if(t.tag==TOP&&i>0&&(f.stack[i-1].tag==LONG||f.stack[i-1].tag==DOUBLE))continue; stkn++;}
            w2o(bos,stkn);
            for(int i=0;i<f.sp;i++){T t=f.stack[i]; if(t.tag==TOP&&i>0&&(f.stack[i-1].tag==LONG||f.stack[i-1].tag==DOUBLE))continue; writeType(bos,t,cp);}
        }
        return bos.toByteArray();
    }

    void writeType(ByteArrayOutputStream bos,T t,Cp cp){
        bos.write(t.tag);
        if(t.tag==OBJECT){
            w2o(bos,cp.addClass(t.name));
        }else if(t.tag==UNINIT){
            w2o(bos,t.newPc);
        }
    }

    static void w2o(OutputStream o,int v){try{o.write((v>>8)&0xFF);o.write(v&0xFF);}catch(IOException e){throw new RuntimeException(e);}}

    // ================= injection =================
    static final class Pc { Cp cp=new Cp(); int cpEnd, thisClass; }

    Pc parse(byte[] data){
        Pc c=new Pc();
        int pos=10;
        int count=u2(data,8);
        c.cp.kind=new byte[count+64];c.cp.obj=new Object[count+64];c.cp.count=count;
        int i=1;
        while(i<count){
            int tag=u1(data,pos++);
            c.cp.kind[i]=(byte)tag;
            switch(tag){
                case 1:{int l=u2(data,pos);StringBuffer sb=new StringBuffer(l);for(int k=0;k<l;k++)sb.append((char)(data[pos+2+k]&0xFF));c.cp.obj[i]=sb.toString();pos+=2+l;break;}
                case 3: case 4: pos+=4;break;
                case 5: case 6: pos+=8;i+=2;continue;
                case 7: case 8: case 16: case 19: case 20:{c.cp.obj[i]=new int[]{u2(data,pos)};pos+=2;break;}
                case 15: pos+=3;break;
                case 9: case 10: case 11: case 12: case 17: case 18:{c.cp.obj[i]=new int[]{u2(data,pos),u2(data,pos+2)};pos+=4;break;}
                default: throw new RuntimeException("cp tag "+tag);
            }
            i++;
        }
        c.cpEnd=pos;
        c.thisClass=u2(data,pos+2);
        return c;
    }

    int skipMembers(byte[] d,int pos){
        int n=u2(d,pos);pos+=2;
        for(int i=0;i<n;i++){int na=u2(d,pos+6);pos+=8;for(int a=0;a<na;a++)pos+=6+s4(d,pos+2);}
        return pos;
    }

    int methodsStart(byte[] d,Pc c){
        int pos=c.cpEnd+6;
        int ifc=u2(d,pos);pos+=2+2*ifc;
        return skipMembers(d,pos);
    }

    byte[] processClass(byte[] data){
        Pc c=parse(data);
        String thisName=c.cp.className(c.thisClass);
        int ms=methodsStart(data,c);
        int mcount=u2(data,ms);
        // phase 1: analyze, collect bodies (cp additions complete before serialization)
        int aPos=ms+2;
        int[] aStarts=new int[mcount];
        byte[][] bodies=new byte[mcount][];
        Meth[] meths=new Meth[mcount];
        int injected=0;
        for(int mi=0;mi<mcount;mi++){
            aStarts[mi]=aPos;
            Meth m=new Meth();
            m.access=u2(data,aPos);
            m.name=c.cp.utf8(u2(data,aPos+2));
            m.desc=c.cp.utf8(u2(data,aPos+4));
            int na=u2(data,aPos+6);
            int apos=aPos+8;
            for(int ai=0;ai<na;ai++){
                int nameIdx=u2(data,apos);
                int alen=s4(data,apos+2);
                if("Code".equals(c.cp.utf8(nameIdx))){
                    m.absStart=apos;
                    m.contentOff=apos+6;
                    m.maxStack=u2(data,m.contentOff);
                    m.maxLocals=u2(data,m.contentOff+2);
                    int clen=s4(data,m.contentOff+4);
                    m.codeOff=m.contentOff+8;
                    m.code=new byte[clen];
                    System.arraycopy(data,m.codeOff,m.code,0,clen);
                    int ep=m.codeOff+clen;
                    int exc=u2(data,ep);
                    m.handlers=new int[exc][];
                    int hp=ep+2;
                    for(int hi=0;hi<exc;hi++){m.handlers[hi]=new int[]{u2(data,hp),u2(data,hp+2),u2(data,hp+4),u2(data,hp+6)};hp+=8;}
                    m.attrCntOff=hp;
                }
                apos+=6+alen;
            }
            aPos=apos;
            meths[mi]=m;
        }
        for(int mi=0;mi<mcount;mi++){
            Meth m=meths[mi];
            if(m.code==null)continue;
            Map frames=analyze(thisName,m,c.cp);
            m.frames=frames;
            byte[] body=emitStackMapBody(m,c.cp);
            if(body!=null){
                bodies[mi]=body;
                injected++;
            }
        }
        if(injected==0)return data;

        int stackMapName=c.cp.addUtf8("StackMap"); // after all frame classes
        int newCount=c.cp.count;
        int origCount=u2(data,8);

        ByteArrayOutputStream out=new ByteArrayOutputStream(data.length+8192);
            // header + pool
            out.write(data,0,8);
            w2o(out,newCount);
            out.write(data,10,c.cpEnd-10);
            for(int i=origCount;i<newCount;i++){
                if(c.cp.kind[i]==1){
                    String s=c.cp.utf8(i);byte[] b=new byte[s.length()];
                    for(int k=0;k<b.length;k++)b[k]=(byte)s.charAt(k);
                    out.write(1);w2o(out,b.length);out.write(b,0,b.length);
                }else if(c.cp.kind[i]==7){
                    out.write(7);w2o(out,((int[])c.cp.obj[i])[0]);
                }else throw new RuntimeException("new cp kind "+c.cp.kind[i]);
            }
            // access/this/super/interfaces/fields
            out.write(data,c.cpEnd,ms-c.cpEnd);
            // methods
            w2o(out,mcount);
            for(int mi=0;mi<mcount;mi++){
                Meth m=meths[mi];
                int na=u2(data,aStarts[mi]+6);
                int apos=aStarts[mi]+8;
                out.write(data,aStarts[mi],8);
                for(int ai=0;ai<na;ai++){
                    int nameIdx=u2(data,apos);
                    int alen=s4(data,apos+2);
                    if(bodies[mi]!=null&&apos==m.absStart){
                        // Code: append StackMap subattr
                        w2o(out,nameIdx);
                        w4o(out,alen+6+bodies[mi].length);
                        out.write(data,m.contentOff,m.attrCntOff-m.contentOff);
                        int subn=u2(data,m.attrCntOff);
                        w2o(out,subn+1);
                        out.write(data,m.attrCntOff+2,apos+6+alen-(m.attrCntOff+2));
                        w2o(out,stackMapName);
                        w4o(out,bodies[mi].length);
                        out.write(bodies[mi],0,bodies[mi].length);
                    }else{
                        out.write(data,apos,6+alen);
                    }
                    apos+=6+alen;
                }
            }
            // class attrs tail
            out.write(data,aPos,data.length-aPos);
        injectedMethods+=injected;
        injectedClasses++;
        return out.toByteArray();
    }

    static void w4o(OutputStream o,long v){try{o.write((int)((v>>24)&0xFF));o.write((int)((v>>16)&0xFF));o.write((int)((v>>8)&0xFF));o.write((int)(v&0xFF));}catch(IOException e){throw new RuntimeException(e);}}

    // ================= driver =================
    public static void main(String[] args) throws Exception {
        if(args.length>0&&"compare".equals(args[0])){compare(args[1],args[2]);return;}
        int base=0;
        if(args.length>0&&"inject".equals(args[0]))base=1;
        if(args.length<base+3){System.out.println("usage: SMGen inject <classesDir> <outDir> <apiJars>");return;}
        File in=new File(args[base]),out=new File(args[base+1]);
        ArrayList urls=new ArrayList();
        urls.add(in.toURI().toURL());
        if(args.length>base+2)for(String s:args[base+2].split(File.pathSeparator))urls.add(new File(s).toURI().toURL());
        URLClassLoader loader=new URLClassLoader((URL[])urls.toArray(new URL[0]),ClassLoader.getSystemClassLoader().getParent());
        SMGen g=new SMGen(loader);
        ArrayList files=new ArrayList();
        collect(in,files);
        int errors=0,done=0;
        for(int i=0;i<files.size();i++){
            File f=(File)files.get(i);
            String rel=in.toURI().relativize(f.toURI()).getPath();
            byte[] result;
            try{ result=g.processClass(read(f)); done++; }
            catch(Throwable t){ System.err.println("ERR "+rel+" : "+t); errors++; continue; }
            File o=new File(out,rel);
            o.getParentFile().mkdirs();
            FileOutputStream fos=new FileOutputStream(o);
            fos.write(result);fos.close();
        }
        System.out.println("SMGEN done="+done+" errors="+errors+" injected_classes="+injectedClasses+" injected_methods="+injectedMethods);
        System.exit(errors==0?0:1);
    }

    static void collect(File d,List out){
        File[] f=d.listFiles();
        if(f==null) throw new RuntimeException("listFiles null on "+d.getAbsolutePath()+" exists="+d.exists()+" isDir="+d.isDirectory());
        for(int i=0;i<f.length;i++){ if(f[i].isDirectory())collect(f[i],out); else if(f[i].getName().endsWith(".class"))out.add(f[i]); }
    }
    static byte[] read(File f)throws IOException{
        byte[] b=new byte[(int)f.length()];
        FileInputStream fi=new FileInputStream(f);int off=0;
        while(off<b.length){int n=fi.read(b,off,b.length-off);if(n<0)break;off+=n;}
        fi.close();return b;
    }
    static void compare(String jarPath,String dir)throws Exception{
        java.util.jar.JarFile jf=new java.util.jar.JarFile(jarPath);
        Enumeration en=jf.entries();
        int same=0,diff=0,missing=0;
        ArrayList diffs=new ArrayList();
        while(en.hasMoreElements()){
            java.util.jar.JarEntry e=(java.util.jar.JarEntry)en.nextElement();
            if(!e.getName().endsWith(".class"))continue;
            InputStream is=jf.getInputStream(e);
            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            byte[] buf=new byte[8192];int n;
            while((n=is.read(buf))!=-1)bos.write(buf,0,n);
            byte[] a=bos.toByteArray();
            File f=new File(dir,e.getName());
            if(!f.exists()){missing++;diffs.add("missing "+e.getName());continue;}
            byte[] b=read(f);
            if(Arrays.equals(a,b))same++;
            else{diff++;diffs.add("diff "+e.getName()+" "+a.length+"->"+b.length);}
        }
        for(int i=0;i<Math.min(40,diffs.size());i++)System.out.println(diffs.get(i));
        System.out.println("compare: same="+same+" diff="+diff+" missing="+missing);
    }
}
