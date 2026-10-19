package code.Rendering;

import code.Gameplay.Map.*;
import code.Rendering.Meshes.*;
import code.Math.*;

/** Conservative room traversal in actual native-camera screen coordinates.
 * The legacy HouseCreator any-common-coordinate/tolerance25 linking is retained.
 * Real containment/monotonic unions, not Room.viewportContains's overlap test.
 * Portal cones use the eye plane, NOT native near128: a doorway closer than the
 * near plane must still reveal the room beyond. No portal backface suppression.
 */
final class PortalMapVisibility {
    private House house;
    private Room[] rooms;
    final int roomCount,portalCount;
    final int[] links,offsets,edges;
    final double[][] vertices;
    private final double[] normal,planePoint;
    private final int[] projected,windows,queue;
    private final boolean[] valid,queued;
    private final double[] a=new double[96],b=new double[96],inverse=new double[12];
    private boolean haveEye,haveSelection;
    private final float[] selectionCamera=new float[16];
    private int selectionWidth,selectionHeight,selectionFx,selectionFy,selectionMask,selectionRooms,selectionSeed;
    boolean cachedFrame;
    private int lastX,lastY,lastZ,lastPart=-1;
    int currentRoom=-1,selectedRooms,portalTests;
    int mask;
    private boolean disposed;

    PortalMapVisibility(Mesh[] meshes){
        roomCount=meshes.length-1;if(roomCount<1 || roomCount>30)throw new IllegalArgumentException("room mask budget");
        Room.chunkSize=Room.chunkSizeRender=0;rooms=new Room[roomCount];
        for(int i=0;i<roomCount;i++)rooms[i]=new Room(meshes[i],i);
        RenderObject[] portals=meshes[roomCount].getPolygons();portalCount=portals.length;
        if(portalCount<1 || portalCount>128)throw new IllegalArgumentException("portal budget");
        vertices=new double[portalCount][];links=new int[portalCount*2];normal=new double[portalCount*3];planePoint=new double[portalCount*3];
        int[] degree=new int[roomCount];
        for(int p=0;p<portalCount;p++){
            Vertex[] points=points(portals[p]);double[] xyz=new double[points.length*3];
            for(int i=0;i<points.length;i++){xyz[i*3]=points[i].x;xyz[i*3+1]=points[i].y;xyz[i*3+2]=points[i].z;}
            vertices[p]=xyz;int found=0;
            for(int r=0;r<roomCount;r++)if(common(meshes[r].getVertices(),points)){
                if(found==2)throw new IllegalArgumentException("ambiguous portal "+p);links[p*2+found++]=r;degree[r]++;
            }
            if(found!=2)throw new IllegalArgumentException("unpaired portal "+p);
            double ax=xyz[3]-xyz[0],ay=xyz[4]-xyz[1],az=xyz[5]-xyz[2],bx=xyz[6]-xyz[0],by=xyz[7]-xyz[1],bz=xyz[8]-xyz[2];
            double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,len=Math.sqrt(nx*nx+ny*ny+nz*nz);
            if(len<0.001)throw new IllegalArgumentException("degenerate portal");normal[p*3]=nx/len;normal[p*3+1]=ny/len;normal[p*3+2]=nz/len;
            for(int k=0;k<3;k++)planePoint[p*3+k]=xyz[k];
            for(int i=0;i<xyz.length;i+=3)if(Math.abs((xyz[i]-xyz[0])*nx/len+(xyz[i+1]-xyz[1])*ny/len+(xyz[i+2]-xyz[2])*nz/len)>0.1)throw new IllegalArgumentException("nonplanar portal");
        }
        offsets=new int[roomCount+1];for(int r=0;r<roomCount;r++)offsets[r+1]=offsets[r]+degree[r];
        edges=new int[portalCount*2];Room[][] neighbours=new Room[roomCount][];int[] fill=new int[roomCount];
        for(int r=0;r<roomCount;r++)neighbours[r]=new Room[degree[r]];
        for(int p=0;p<portalCount;p++)for(int side=0;side<2;side++){
            int r=links[p*2+side],other=links[p*2+1-side],index=fill[r]++;edges[offsets[r]+index]=p;neighbours[r][index]=rooms[other];
        }
        house=new House(rooms,neighbours);projected=new int[portalCount*4];windows=new int[roomCount*4];valid=new boolean[portalCount];queued=new boolean[roomCount];queue=new int[roomCount+1];
    }
    private static Vertex[] points(RenderObject p){
        if(p instanceof Polygon4V){Polygon4V q=(Polygon4V)p;return new Vertex[]{q.a,q.b,q.c,q.d};}
        if(p instanceof Polygon3V){Polygon3V q=(Polygon3V)p;return new Vertex[]{q.a,q.b,q.c};}
        throw new IllegalArgumentException("portal polygon");
    }
    private static boolean common(Vertex[] mesh,Vertex[] portal){
        for(int i=0;i<portal.length;i++)for(int j=0;j<mesh.length;j++)if(Math.abs(portal[i].x-mesh[j].x)<=25 && Math.abs(portal[i].y-mesh[j].y)<=25 && Math.abs(portal[i].z-mesh[j].z)<=25)return true;return false;
    }
    private void invert(float[] m){
        double a=m[0],b=m[1],c=m[2],d=m[4],e=m[5],f=m[6],g=m[8],h=m[9],i=m[10];
        double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        if(Double.isNaN(det) || Double.isInfinite(det) || Math.abs(det)<.00001)throw new IllegalArgumentException("invalid native camera");
        inverse[0]=(e*i-f*h)/det;inverse[1]=(c*h-b*i)/det;inverse[2]=(b*f-c*e)/det;
        inverse[4]=(f*g-d*i)/det;inverse[5]=(a*i-c*g)/det;inverse[6]=(c*d-a*f)/det;
        inverse[8]=(d*h-e*g)/det;inverse[9]=(b*g-a*h)/det;inverse[10]=(a*e-b*d)/det;
        for(int r=0;r<3;r++)inverse[r*4+3]=-(inverse[r*4]*m[3]+inverse[r*4+1]*m[7]+inverse[r*4+2]*m[11]);
    }
    private void project(int p,float[] camera,int width,int height,int fx,int fy){
        double[] xyz=vertices[p];int box=p*4;valid[p]=false;
        double distance=0;boolean inside=true;
        for(int k=0;k<3;k++){
            double eye=camera[k*4+3],low=xyz[k],high=xyz[k];distance+=(eye-planePoint[p*3+k])*normal[p*3+k];
            for(int v=3;v<xyz.length;v+=3){low=Math.min(low,xyz[v+k]);high=Math.max(high,xyz[v+k]);}
            if(eye<low-4 || eye>high+4)inside=false;
        }
        if(inside && Math.abs(distance)<=4){projected[box]=projected[box+1]=0;projected[box+2]=width;projected[box+3]=height;valid[p]=true;return;}
        for(int v=0;v<xyz.length;v+=3)for(int r=0;r<3;r++)a[v+r]=inverse[r*4]*xyz[v]+inverse[r*4+1]*xyz[v+1]+inverse[r*4+2]*xyz[v+2]+inverse[r*4+3];
        double[] src=a,dst=b;int size=xyz.length/3;
        for(int plane=0;plane<5 && size>0;plane++){
            int count=0;int previous=size-1;double dp=distance(src,previous,plane,width,height,fx,fy);
            for(int v=0;v<size;v++){
                double dv=distance(src,v,plane,width,height,fx,fy);boolean vp=dv>=0,pp=dp>=0;
                if(vp!=pp){double t=dp/(dp-dv);for(int k=0;k<3;k++)dst[count*3+k]=src[previous*3+k]+t*(src[v*3+k]-src[previous*3+k]);count++;}
                if(vp){for(int k=0;k<3;k++)dst[count*3+k]=src[v*3+k];count++;}
                previous=v;dp=dv;
            }
            double[] swap=src;src=dst;dst=swap;size=count;
        }
        if(size==0)return;
        double minX=width,minY=height,maxX=0,maxY=0;
        for(int v=0;v<size;v++){
            double depth=-src[v*3+2],x=width*.5+fx*src[v*3]/depth,y=height*.5-fy*src[v*3+1]/depth;
            if(Double.isNaN(x)||Double.isInfinite(x)||Double.isNaN(y)||Double.isInfinite(y))throw new IllegalStateException("portal projection");
            minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
        }
        projected[box]=Math.max(0,(int)Math.floor(minX)-2);projected[box+1]=Math.max(0,(int)Math.floor(minY)-2);
        projected[box+2]=Math.min(width,(int)Math.ceil(maxX)+2);projected[box+3]=Math.min(height,(int)Math.ceil(maxY)+2);
        valid[p]=projected[box]<projected[box+2] && projected[box+1]<projected[box+3];
    }
    private static double distance(double[] v,int i,int plane,int w,int h,int fx,int fy){
        double x=v[i*3],y=v[i*3+1],d=-v[i*3+2];
        return plane==0?d-.015625:plane==1?fx*x+w*.5*d:plane==2?-fx*x+w*.5*d:plane==3?-fy*y+h*.5*d:fy*y+h*.5*d;
    }
    int select(boolean enabled,float[] camera,int width,int height,int fx,int fy){
        if(disposed)throw new IllegalStateException("disposed portal selector");
        int x=(int)Math.floor(camera[3]+.5),y=(int)Math.floor(camera[7]+.5),z=(int)Math.floor(camera[11]+.5);
        if(!haveEye || x!=lastX || y!=lastY || z!=lastZ){lastPart=house.calcPart(lastPart,x,y,z,true);lastX=x;lastY=y;lastZ=z;haveEye=true;}
        currentRoom=lastPart;portalTests=0;cachedFrame=false;
        if(!enabled || currentRoom<0){mask=(1<<roomCount)-1;selectedRooms=roomCount;return mask;}
        boolean same=haveSelection && selectionSeed==currentRoom && selectionWidth==width && selectionHeight==height && selectionFx==fx && selectionFy==fy;
        if(same)for(int i=0;i<16;i++)if(selectionCamera[i]!=camera[i]){same=false;break;}
        if(same){mask=selectionMask;selectedRooms=selectionRooms;cachedFrame=true;return mask;}
        invert(camera);for(int p=0;p<portalCount;p++)project(p,camera,width,height,fx,fy);
        int result=traverse(currentRoom,width,height);
        System.arraycopy(camera,0,selectionCamera,0,16);selectionWidth=width;selectionHeight=height;selectionFx=fx;selectionFy=fy;selectionMask=result;selectionRooms=selectedRooms;selectionSeed=currentRoom;haveSelection=true;
        return result;
    }
    private int traverse(int seed,int width,int height){
        for(int r=0;r<roomCount;r++){windows[r*4]=width;windows[r*4+1]=height;windows[r*4+2]=windows[r*4+3]=0;queued[r]=false;}
        int head=0,tail=0;queue[tail++]=seed;queued[seed]=true;int s=seed*4;windows[s]=windows[s+1]=0;windows[s+2]=width;windows[s+3]=height;mask=1<<seed;
        while(head!=tail){
            int room=queue[head++];if(head==queue.length)head=0;queued[room]=false;int w=room*4;
            for(int e=offsets[room];e<offsets[room+1];e++){
                int p=edges[e],box=p*4;portalTests++;if(!valid[p])continue;
                int x1=Math.max(windows[w],projected[box]),y1=Math.max(windows[w+1],projected[box+1]),x2=Math.min(windows[w+2],projected[box+2]),y2=Math.min(windows[w+3],projected[box+3]);
                if(x1>=x2 || y1>=y2)continue;
                int other=links[p*2]==room?links[p*2+1]:links[p*2],o=other*4;boolean seen=(mask&(1<<other))!=0;
                if(seen && windows[o]<=x1 && windows[o+1]<=y1 && windows[o+2]>=x2 && windows[o+3]>=y2)continue;
                windows[o]=Math.min(windows[o],x1);windows[o+1]=Math.min(windows[o+1],y1);windows[o+2]=Math.max(windows[o+2],x2);windows[o+3]=Math.max(windows[o+3],y2);mask|=1<<other;
                if(!queued[other]){queue[tail++]=other;if(tail==queue.length)tail=0;queued[other]=true;}
            }
        }
        selectedRooms=0;for(int r=0;r<roomCount;r++)if((mask&(1<<r))!=0)selectedRooms++;return mask;
    }
    void dispose(){house=null;rooms=null;disposed=true;}
}
