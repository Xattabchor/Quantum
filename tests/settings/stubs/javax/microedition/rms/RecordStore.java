package javax.microedition.rms;

import java.util.Hashtable;
import java.util.Vector;

/** In-memory RMS test double. Never packaged into the MIDlet. */
public final class RecordStore {
    private static final Hashtable<String,RecordStore> stores=new Hashtable<String,RecordStore>();
    public static boolean fail;
    private final Vector<byte[]> records=new Vector<byte[]>();
    public static RecordStore openRecordStore(String name,boolean create) {
        if(fail) throw new IllegalStateException("RMS unavailable");
        RecordStore store=stores.get(name);
        if(store==null) {
            if(!create) throw new IllegalArgumentException("missing store");
            store=new RecordStore();stores.put(name,store);
        }
        return store;
    }
    public static void deleteRecordStore(String name) {
        if(fail) throw new IllegalStateException("RMS unavailable");stores.remove(name);
    }
    public byte[] getRecord(int id) {return records.elementAt(id-1).clone();}
    public int addRecord(byte[] data,int offset,int size) {
        byte[] copy=new byte[size];System.arraycopy(data,offset,copy,0,size);records.add(copy);return records.size();
    }
    public int getNumRecords() {return records.size();}
    public void closeRecordStore() {}
}
