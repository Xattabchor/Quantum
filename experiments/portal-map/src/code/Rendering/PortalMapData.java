package code.Rendering;

import code.Gameplay.Map.Room;
import code.Rendering.Meshes.*;
import code.utils.*;
import java.io.*;

/** User archive is retained byte-for-byte under its own resource namespace. */
final class PortalMapData {
    static final String PREFIX="/portal-map";
    static GameIni config(){return new GameIni(StringTools.getStringFromResource(PREFIX+"/level1.txt"),true);}
    static Mesh[] load(GameIni config)throws IOException {
        if(config.getInt("DMODE",-1)!=13 || config.getInt("DIST",-1)!=40000 || config.getFloat("WORLD_SCALE",0)!=6)throw new IOException("unexpected portal-map config");
        DirectX7.standartDrawmode=13;Main.fogQ=2;Main.mipMapping=false;
        MultyTexture textures=new MultyTexture(PREFIX+config.get("WORLD_TEXTURE"),true);
        Mesh[] meshes=Room.loadMeshes(PREFIX+config.get("WORLD_MODEL"),6,6,6,textures);
        if(meshes==null || meshes.length!=16)throw new IOException("expected 15 rooms + portals");
        for(int i=0;i<meshes.length;i++)if(meshes[i]==null)throw new IOException("partial room load");
        loadLightmap(meshes,PortalMapData.class.getResourceAsStream(PREFIX+config.get("LIGHTMAP")));
        return meshes;
    }
    /** Exact mode13 VLA order: all R samples, bool, optional G/B pairs.
     * Includes the last portal mesh to validate the entire stream. Gray samples
     * stay RGB with equal channels: no lossy downgrade or swallowed I/O errors. */
    static void loadLightmap(Mesh[] meshes,InputStream stream)throws IOException {
        if(stream==null)throw new IOException("RGB lightmap missing");DataInputStream in=new DataInputStream(stream);
        try {
            int[] red=new int[4];
            for(int m=0;m<meshes.length;m++){
                RenderObject[] faces=meshes[m].getPolygons();
                for(int f=0;f<faces.length;f++){
                    RenderObject p=faces[f];int n=p instanceof ColorLightedPolygon4V?4:p instanceof ColorLightedPolygon3V?3:0;
                    if(n==0)throw new IOException("mode13 colored geometry required");
                    for(int v=0;v<n;v++)red[v]=in.readByte()+128;
                    int flag=in.readUnsignedByte();if(flag>1)throw new IOException("invalid RGB flag");
                    for(int v=0;v<n;v++){int g=flag==1?in.readByte()+128:red[v],b=flag==1?in.readByte()+128:red[v];setColor(p,v,red[v],g,b);}
                }
            }
            if(in.read()!=-1)throw new IOException("trailing RGB lightmap bytes");
        }finally{in.close();}
    }
    private static void setColor(RenderObject p,int v,int r,int g,int b){
        if(p instanceof ColorLightedPolygon4V){ColorLightedPolygon4V q=(ColorLightedPolygon4V)p;
            if(v==0){q.ar=r;q.ag=g;q.ab=b;}else if(v==1){q.br=r;q.bg=g;q.bb=b;}else if(v==2){q.cr=r;q.cg=g;q.cb=b;}else{q.dr=r;q.dg=g;q.db=b;}
        }else{ColorLightedPolygon3V q=(ColorLightedPolygon3V)p;
            if(v==0){q.ar=r;q.ag=g;q.ab=b;}else if(v==1){q.br=r;q.bg=g;q.bb=b;}else{q.cr=r;q.cg=g;q.cb=b;}
        }
    }
}
