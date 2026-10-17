package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.utils.IniFile;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;

/** Real asset bytes with instrumented M3G, NOT a level runner or FPS benchmark.
 * Desktop ImageIO only decodes fixtures; it is never packaged in the MIDlet.
 * Uses production IniFile, RawImage, Skybox and the current rendering adapter.
 */
public final class GameResourcesTest {
    private static void check(boolean value,String message) { M3GTest.check(value,message); }
    private static byte[] bytes(String name) throws Exception {
        InputStream input=GameResourcesTest.class.getResourceAsStream("/"+name);
        check(input!=null,"resource missing from test classpath: "+name);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int size;
            while((size=input.read(buffer))!=-1) out.write(buffer,0,size);
            return out.toByteArray();
        } finally {input.close();}
    }
    private static int rgb(String value) {
        if(value==null) return 0;
        String[] channels=value.split(",");
        return Integer.parseInt(channels[0].trim())<<16
            |Integer.parseInt(channels[1].trim())<<8|Integer.parseInt(channels[2].trim());
    }
    private static void panorama(IniFile level,String name) throws Exception {
        String[] params=level.get("SKYBOX_2D").split(",");String texturePath=params[0].trim();
        BufferedImage image;
        try(InputStream input=GameResourcesTest.class.getResourceAsStream(texturePath)) {
            check(input!=null,"panorama texture missing: "+texturePath);image=ImageIO.read(input);
        }
        check(image!=null,"cannot decode panorama: "+texturePath);
        int w=image.getWidth(),h=image.getHeight();
        Texture texture=new Texture(new RawImage(image.getRGB(0,0,w,h,null,0,w),w,h),false,null);
        int[] original=texture.rImg.img.clone();
        Skybox sky=new Skybox(texture,level.getFloat("SKYBOX_REPEAT_X",2),level.getFloat("SKYBOX_REPEAT_Y",1),
            params.length>2?Integer.parseInt(params[2].trim()):-91,
            params.length>1?Float.parseFloat(params[1].trim()):1,
            params.length>3?Float.parseFloat(params[3].trim()):0,
            rgb(level.get("SKYBOX_COLOR")),rgb(level.get("SKYBOX_GROUND_COLOR")));
        DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
        ScreenLines rain=new ScreenLines();
        for(int i=0;i<16;i++) rain.line(8+i*14,20+i*12,9+i*14,30+i*12,1,0x99d49f,false);
        int maxDraws=0,maxBarriers=0;
        for(int pitch:new int[]{-60,0,60}) for(int yaw:new int[]{0,90,180,270}) {
            // Exercise the real viewport/crop, not an unconditional full-sky shortcut.
            sky.rotateX=pitch;sky.rotateY=yaw;
            for(int frame=0;frame<3;frame++) {
                sky.x1=0;sky.y1=0;sky.x2=g.width;sky.y2=g.height;
                g.beginFrameStats();sky.render(null,g);g.renderBackground();
                check(g.renderGameFrameImage(null,false,false,null,0,rain),"native weather failed: "+name);
                check(g.isM3GActive(),"resource triggered backend failure: "+name+" "+g.getRendererError());
                if(frame>0) {
                    check(g.getM3GDrawCalls()>=1 && g.getM3GDrawCalls()<=4,"sky+rain exceeded four draws: "+name);
                    check(g.getM3GTextureUploads()==0,"warm panorama reuploaded texture: "+name);
                    check(g.getM3GCpuBarriers()<=1,"extra warm CPU barriers: "+name);
                    maxDraws=Math.max(maxDraws,g.getM3GDrawCalls());
                    maxBarriers=Math.max(maxBarriers,g.getM3GCpuBarriers());
                }
            }
        }
        check(Arrays.equals(original,texture.rImg.img),"test altered source panorama: "+name);
        System.out.println("PASS real panorama "+name+" "+texturePath+" "+w+"x"+h
            +" / original config / 12 poses / 16 rain / warm max D:"+maxDraws+" B:"+maxBarriers+" T:0 (API doubles, not FPS)");
        g.destroy();sky.destroy();
    }
    public static void main(String[] args) throws Exception {
        int levels=0,panoramas=0;
        // Enumerate, rather than hardcode, the levels supplied by the user.
        try(JarFile assets=new JarFile(args[0])) {
            Enumeration<JarEntry> entries=assets.entries();
            while(entries.hasMoreElements()) {
                String name=entries.nextElement().getName();
                check(!name.endsWith(".class"),"old game classes entered resource classpath");
                if(!name.matches("level[0-9]+\\.txt")) continue;
                IniFile level=new IniFile(new String(bytes(name),"windows-1251"),false);levels++;
                check(level.get("WORLD_MODEL")!=null,"level config did not parse: "+name);
                if(level.get("SKYBOX_2D")!=null) {panorama(level,name);panoramas++;}
            }
        }
        check(levels>0 && panoramas>0,"no level/panorama fixtures tested");
        System.out.println("PASS real resource classpath / "+levels+" level configs / "+panoramas+" real 2D skies / no original resource edits");
    }
}
