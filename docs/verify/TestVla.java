import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import tools.vlaconv.VlaCodec;

/**
 * Проверка кодека VlaCodec (13 цвет <-> 9 серый) без MIDP: CLDC на JRE.
 * Печатает:
 *  OK/FAIL <кейс> [детали]
 *  STATS <кейс> p3=.. p4=.. bytes=..
 *  HEX <кейс> <hex>
 *  ALL OK / TOTAL FAILS
 */
public class TestVla {
    static int failures=0;
    public static void main(String[] args) throws Exception {
        synthGray();
        synthColor();
        cross();
        roundTrip();
        errTrunc();
        errExtra();
        System.out.println(failures==0?"ALL OK":"TOTAL FAILS "+failures);
    }
    static void synthGray() throws Exception {
        int[][] counts=new int[][]{{2,1}};
        byte[] mesh=VlaCodec.buildMeshBytes(counts);
        // серый vla 2 три (3б) +1 квад (4б) =10б
        int[] grays=new int[]{10,20,30, 40,50,60, 70,80,90,100};
        byte[] gray=buildGray(counts, grays);
        System.out.println("STATS gray p3="+counts[0][0]+" p4="+counts[0][1]+" bytes="+gray.length);
        // серый->цвет->серый roundtrip
        byte[] color=VlaCodec.grayToColor(gray, mesh);
        byte[] gray2=VlaCodec.colorToGray(color, mesh);
        boolean eq=equals(gray,gray2);
        report("gray-rt", eq, "gray roundtrip mismatch "+hex(gray)+" vs "+hex(gray2));
        // цветной должен быть сжатый (false): 2* (3+1) + (4+1)=4+4+5=13б
        report("gray-color-size", color.length==13, "color len "+color.length+" !=13");
        System.out.println("HEX gray "+hex(gray));
        System.out.println("HEX color-from-gray "+hex(color));
    }
    static void synthColor() throws Exception {
        int[][] counts=new int[][]{{1,1},{1,0}};
        byte[] mesh=VlaCodec.buildMeshBytes(counts);
        // цветной: полигон0 3V цветной (10 байт), полигон1 4V серый уже (5 байт), полигон2 3V цветной (10 байт) =25б
        ByteArrayOutputStream bos=new ByteArrayOutputStream();
        DataOutputStream dos=new DataOutputStream(bos);
        dos.writeByte(10-128); dos.writeByte(20-128); dos.writeByte(30-128); dos.writeBoolean(true);
        dos.writeByte(11-128); dos.writeByte(12-128); dos.writeByte(21-128); dos.writeByte(22-128); dos.writeByte(31-128); dos.writeByte(32-128);
        dos.writeByte(40-128); dos.writeByte(50-128); dos.writeByte(60-128); dos.writeByte(70-128); dos.writeBoolean(false);
        dos.writeByte(100-128); dos.writeByte(110-128); dos.writeByte(120-128); dos.writeBoolean(true);
        dos.writeByte(101-128); dos.writeByte(102-128); dos.writeByte(111-128); dos.writeByte(112-128); dos.writeByte(121-128); dos.writeByte(122-128);
        dos.flush(); byte[] color=bos.toByteArray();
        byte[] gray=VlaCodec.colorToGray(color, mesh);
        int[] expect=new int[]{11,21,31,40,50,60,70,101,111,121};
        boolean ok=gray.length==expect.length;
        if(ok) for(int i=0;i<expect.length;i++) if(((gray[i]+128)&0xFF)!=expect[i]) ok=false;
        report("color-gray", ok, "gray mismatch "+hex(gray));
        System.out.println("STATS color p3=1+1 p4=1 bytes="+color.length+" -> gray "+gray.length);
        System.out.println("HEX color "+hex(color));
        System.out.println("HEX gray-from-color "+hex(gray));
    }
    static void cross() throws Exception {
        int[][] counts=new int[][]{{1,0}};
        byte[] mesh=VlaCodec.buildMeshBytes(counts);
        // цветной с уже серыми полигонами (colored false) должен дать тот же серый что и прямой
        ByteArrayOutputStream bos=new ByteArrayOutputStream();
        DataOutputStream dos=new DataOutputStream(bos);
        dos.writeByte(77-128); dos.writeByte(77-128); dos.writeByte(77-128); dos.writeBoolean(false);
        dos.flush(); byte[] colorGray=bos.toByteArray();
        byte[] gray=VlaCodec.colorToGray(colorGray, mesh);
        report("cross-gray-already", ((gray[0]+128)&0xFF)==77 && gray.length==3, "cross gray already");
        // обратный: серый 77 -> цветной false -> снова серый 77
        byte[] grayOrig=new byte[]{(byte)(77-128),(byte)(88-128),(byte)(99-128)};
        byte[] color2=VlaCodec.grayToColor(grayOrig, mesh);
        // color2 должен быть 3+1=4 байта
        report("cross-gray-color-size", color2.length==4, "len "+color2.length);
        byte[] gray2=VlaCodec.colorToGray(color2, mesh);
        report("cross-gray-color-rt", equals(grayOrig,gray2), "roundtrip");
    }
    static void roundTrip() throws Exception {
        int[][] counts=new int[][]{{5,5}};
        byte[] mesh=VlaCodec.buildMeshBytes(counts);
        // случайные цвета
        java.util.Random rnd=new java.util.Random(1);
        ByteArrayOutputStream bos=new ByteArrayOutputStream();
        DataOutputStream dos=new DataOutputStream(bos);
        for(int i=0;i<5;i++){ int ar=rnd.nextInt(256), br=rnd.nextInt(256), cr=rnd.nextInt(256);
            boolean colored=rnd.nextInt(2)==0;
            dos.writeByte(ar-128); dos.writeByte(br-128); dos.writeByte(cr-128); dos.writeBoolean(colored);
            if(colored){ dos.writeByte(rnd.nextInt(256)-128); dos.writeByte(rnd.nextInt(256)-128); dos.writeByte(rnd.nextInt(256)-128); dos.writeByte(rnd.nextInt(256)-128); dos.writeByte(rnd.nextInt(256)-128); dos.writeByte(rnd.nextInt(256)-128); }
        }
        for(int i=0;i<5;i++){ int ar=rnd.nextInt(256), br=rnd.nextInt(256), cr=rnd.nextInt(256), dr=rnd.nextInt(256);
            boolean colored=rnd.nextInt(2)==0;
            dos.writeByte(ar-128); dos.writeByte(br-128); dos.writeByte(cr-128); dos.writeByte(dr-128); dos.writeBoolean(colored);
            if(colored){ for(int k=0;k<8;k++) dos.writeByte(rnd.nextInt(256)-128); }
        }
        dos.flush(); byte[] color=bos.toByteArray();
        byte[] gray=VlaCodec.colorToGray(color, mesh);
        byte[] color2=VlaCodec.grayToColor(gray, mesh);
        byte[] gray2=VlaCodec.colorToGray(color2, mesh);
        report("rnd-rt", equals(gray,gray2), "random roundtrip failed");
    }
    static void errTrunc() {
        try{
            int[][] c=new int[][]{{1,0}}; byte[] mesh=VlaCodec.buildMeshBytes(c);
            byte[] trunc=new byte[]{(byte)(10-128)}; // обрыв
            VlaCodec.colorToGray(trunc, mesh);
            report("err-trunc", false, "no exception");
        }catch(IllegalArgumentException e){ report("err-trunc", true, ""); }
        catch(Exception e){ report("err-trunc", false, "wrong exc "+e); }
    }
    static void errExtra(){
        try{
            int[][] c=new int[][]{{1,0}}; byte[] mesh=VlaCodec.buildMeshBytes(c);
            byte[] gray=new byte[]{(byte)(1-128),(byte)(2-128),(byte)(3-128),(byte)(4-128)}; // 1 полигон 3V ожидает 3б, дано 4
            VlaCodec.grayToColor(gray, mesh);
            report("err-extra", false, "no exception");
        }catch(IllegalArgumentException e){ report("err-extra", true, ""); }
        catch(Exception e){ report("err-extra", false, "wrong "+e); }
    }
    static byte[] buildGray(int[][] counts, int[] grays){
        try{
            ByteArrayOutputStream bos=new ByteArrayOutputStream(); DataOutputStream dos=new DataOutputStream(bos);
            int p=0; for(int m=0;m<counts.length;m++){
                for(int i=0;i<counts[m][0];i++){ dos.writeByte(grays[p++]-128); dos.writeByte(grays[p++]-128); dos.writeByte(grays[p++]-128); }
                for(int i=0;i<counts[m][1];i++){ dos.writeByte(grays[p++]-128); dos.writeByte(grays[p++]-128); dos.writeByte(grays[p++]-128); dos.writeByte(grays[p++]-128); }
            }
            dos.flush(); return bos.toByteArray();
        }catch(Exception e){ throw new RuntimeException(e.toString()); }
    }
    static void report(String n, boolean ok, String d){ if(ok) System.out.println("OK "+n); else{ failures++; System.out.println("FAIL "+n+" "+d); } }
    static boolean equals(byte[] a, byte[] b){ if(a.length!=b.length) return false; for(int i=0;i<a.length;i++) if(a[i]!=b[i]) return false; return true; }
    static String hex(byte[] a){ StringBuffer sb=new StringBuffer(); for(int i=0;i<a.length;i++){ int v=a[i]&0xFF; if(v<16) sb.append('0'); sb.append(Integer.toHexString(v)); } return sb.toString(); }
}
