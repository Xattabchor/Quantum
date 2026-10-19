package code.Rendering;
import code.Gameplay.Map.Room;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.utils.*;
import java.io.*;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;
/** Offline only: exports the exact native triangle decomposition of immutable Tex3. */
public final class StaticRoomBspExport {
    static Mesh load()throws Exception {
        Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        String text=StringTools.getStringFromResource("/level1.txt"),begin="[WTEX_BEGIN]",end="[WTEX_END]";int a=text.indexOf(begin),b=text.indexOf(end);
        String[] lines=text.substring(a+begin.length(),b).trim().split("\\r?\\n");MultyTexture textures=new MultyTexture(lines);
        GameIni config=new GameIni(text.substring(0,a)+text.substring(b+end.length()),true);DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);
        float scale=config.getFloat("WORLD_SCALE",1);Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,textures);
        StaticRoomTexturesPreview.loadLightmap(meshes,StaticRoomBspExport.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],StaticRoomBspExport.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
    }
    public static void main(String[] args)throws Exception {
        StaticRoomBvhMesh mesh=new StaticRoomBvhMesh(load(),2,true);mesh.setNearPlane(128);mesh.setCullingEnabled(false);
        Matrix pose=new Matrix();mesh.render(Image.createImage(240,320).getGraphics(),pose,240,320,212,212,60000);
        if(Graphics3D.history.size()!=3 || mesh.submittedTriangles!=4750)throw new AssertionError("wrong baseline");
        DataOutputStream out=new DataOutputStream(new FileOutputStream(args[0]));out.writeInt(0x51425349);out.writeInt(4750);
        int material=0;
        for(Graphics3D.Draw d:Graphics3D.history){
            int offset=0;
            for(int length:d.lengths){
                for(int i=2;i<length;i++){
                    out.writeByte(material);
                    int[] corners={(i%2==0?d.indices[offset+i-2]:d.indices[offset+i-1]),(i%2==0?d.indices[offset+i-1]:d.indices[offset+i-2]),d.indices[offset+i]};
                    for(int v:corners){
                        for(int k=0;k<3;k++)out.writeDouble(d.positions[v*3+k]*d.scale+d.bias[k]);
                        for(int k=0;k<2;k++)out.writeDouble(d.coords[v*2+k]);
                        for(int k=0;k<3;k++)out.writeDouble(d.colors[v*4+k]&255);
                    }
                }
                offset+=length;
            }
            material++;
        }
        out.close();mesh.dispose();System.out.println("Exported 4750 native triangles; canonical alternating strip winding, original UV/baked colors/materials.");
    }
}
