package code.Rendering;

import code.Gameplay.Map.Room;
import code.Rendering.Meshes.Mesh;
import code.utils.GameIni;
import code.utils.Main;
import java.io.IOException;

/** Original geometry/UV/RGB light; explicit user-selected sym9 map-selo atlas. */
final class PortalMapBvhData {
    static Mesh[] load(GameIni config)throws IOException {
        if(config.getInt("DMODE",-1)!=13 || config.getInt("DIST",-1)!=40000 || config.getFloat("WORLD_SCALE",0)!=6)throw new IOException("unexpected portal-map config");
        DirectX7.standartDrawmode=13;Main.fogQ=2;Main.mipMapping=false;
        MultyTexture textures=new MultyTexture("/portal-map-bvh/map-selo.png",true);
        Mesh[] meshes=Room.loadMeshes(PortalMapData.PREFIX+config.get("WORLD_MODEL"),6,6,6,textures);
        if(meshes==null || meshes.length!=16)throw new IOException("expected 15 rooms + portals");
        for(int i=0;i<meshes.length;i++)if(meshes[i]==null)throw new IOException("partial room load");
        PortalMapData.loadLightmap(meshes,PortalMapData.class.getResourceAsStream(PortalMapData.PREFIX+config.get("LIGHTMAP")));
        return meshes;
    }
}
