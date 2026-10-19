package code.Rendering;

/** Sparse world-unit X/Z grid. Whole-face AABBs expand each occupied cell;
 * centroids choose membership, NEVER stand in for the bounds used for culling.
 * Same room origin for all materials. No neighbour-radius cut, no face splitting.
 * Cold allocation only; select reuses its byte arrays. */
final class StaticRoomChunkGrid {
    final int size,originX,originZ;
    final int[] faceCells,bounds;
    final byte[] selected;
    private final byte[] cellsVisible;
    int tests,culled,visible;
    boolean changed;
    private boolean initialized;
    StaticRoomChunkGrid(int[] source,int x,int z,int size) {
        if(size<=0 || source.length==0 || source.length%6!=0)throw new IllegalArgumentException("grid bounds/size");
        this.size=size;originX=x;originZ=z;
        int n=source.length/6,count=0;
        faceCells=new int[n];selected=new byte[n];
        int[] xs=new int[n],zs=new int[n],work=new int[n*6];
        for(int f=0;f<n;f++) {
            int p=f*6;
            for(int k=0;k<3;k++)if(source[p+k]>source[p+k+3])throw new IllegalArgumentException("inverted bounds");
            long cx=(long)source[p]+source[p+3]-2L*x,cz=(long)source[p+2]+source[p+5]-2L*z;
            // Floor division, also correct for synthetic faces left of origin.
            int gx=floor(cx,2L*size),gz=floor(cz,2L*size),cell=0;
            while(cell<count && (xs[cell]!=gx || zs[cell]!=gz))cell++;
            if(cell==count) {
                xs[cell]=gx;zs[cell]=gz;count++;
                for(int k=0;k<3;k++){work[cell*6+k]=Integer.MAX_VALUE;work[cell*6+k+3]=Integer.MIN_VALUE;}
            }
            faceCells[f]=cell;
            for(int k=0;k<3;k++){work[cell*6+k]=Math.min(work[cell*6+k],source[p+k]);work[cell*6+k+3]=Math.max(work[cell*6+k+3],source[p+k+3]);}
        }
        bounds=new int[count*6];System.arraycopy(work,0,bounds,0,bounds.length);cellsVisible=new byte[count];
    }
    private static int floor(long value,long divisor){long q=value/divisor;if(value<0 && value%divisor!=0)q--;return (int)q;}
    void select(StaticRoomVisibility frustum) {
        tests=cellsVisible.length;culled=0;changed=!initialized;
        for(int c=0;c<cellsVisible.length;c++) {
            boolean outside=frustum.outside(bounds,c*6);byte value=(byte)(outside?0:1);
            if(value!=cellsVisible[c])changed=true;cellsVisible[c]=value;if(outside)culled++;
        }
        initialized=true;if(!changed)return;visible=0;
        for(int f=0;f<selected.length;f++){selected[f]=cellsVisible[faceCells[f]];if(selected[f]!=0)visible++;}
    }
}
