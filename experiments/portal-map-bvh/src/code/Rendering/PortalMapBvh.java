package code.Rendering;

/** Cold-built median BVH of full source-face AABBs. At most8 faces per leaf.
 * Traversal only marks faces; submission always follows original strip order.
 * No occlusion guessing, near-plane projection, per-frame sorting or allocation.
 */
final class PortalMapBvh {
    private final int[] bounds,right,start,count,order;
    final byte[] selected;
    int tests,culled,visible;
    private int nodes;

    PortalMapBvh(int[] faceBounds) {
        int n=faceBounds.length/6;
        if(n<1 || faceBounds.length!=n*6)throw new IllegalArgumentException("face bounds");
        // A median split gives >=4 faces per non-root leaf. This is a safe
        // bound on node capacity without reserving two nodes per face.
        int capacity=4*((n+7)/8);
        bounds=new int[capacity*6];right=new int[capacity];start=new int[capacity];count=new int[capacity];
        order=new int[n];selected=new byte[n];for(int i=0;i<n;i++)order[i]=i;
        build(faceBounds,0,n);
    }
    private int centre(int[] source,int face,int axis){int p=face*6+axis;return source[p]+source[p+3];}
    private void sort(int[] source,int lo,int hi,int axis) {
        int i=lo,j=hi,pivot=centre(source,order[(lo+hi)/2],axis);
        while(i<=j) {
            while(centre(source,order[i],axis)<pivot)i++;
            while(centre(source,order[j],axis)>pivot)j--;
            if(i<=j){int swap=order[i];order[i++]=order[j];order[j--]=swap;}
        }
        if(lo<j)sort(source,lo,j,axis);if(i<hi)sort(source,i,hi,axis);
    }
    private int build(int[] source,int first,int size) {
        int node=nodes++,p=node*6;
        for(int k=0;k<3;k++){bounds[p+k]=Integer.MAX_VALUE;bounds[p+3+k]=Integer.MIN_VALUE;}
        for(int f=first;f<first+size;f++)for(int k=0;k<3;k++) {
            int src=order[f]*6;bounds[p+k]=Math.min(bounds[p+k],source[src+k]);bounds[p+3+k]=Math.max(bounds[p+3+k],source[src+3+k]);
        }
        if(size<=8){start[node]=first;count[node]=size;return node;}
        int axis=0;for(int k=1;k<3;k++)if(bounds[p+3+k]-bounds[p+k]>bounds[p+3+axis]-bounds[p+axis])axis=k;
        sort(source,first,first+size-1,axis);int leftSize=size/2;
        build(source,first,leftSize);right[node]=build(source,first+leftSize,size-leftSize);return node;
    }
    void select(PortalMapBvhFrustum frustum) {
        for(int f=0;f<selected.length;f++)selected[f]=0;
        tests=culled=visible=0;visit(0,frustum);
    }
    private void visit(int node,PortalMapBvhFrustum frustum) {
        tests++;if(frustum.outside(bounds,node*6)){culled++;return;}
        if(count[node]>0) {
            int end=start[node]+count[node];for(int f=start[node];f<end;f++){selected[order[f]]=1;visible++;}
        } else {visit(node+1,frustum);visit(right[node],frustum);}
    }
}
