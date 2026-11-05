package javax.microedition.rms;

import java.util.Hashtable;

/** In-memory RMS; -Dstub.rms=deny makes every store fail like a locked phone. */
public class RecordStore {
    private static final Hashtable STORES = new Hashtable();
    private final String name;
    private final java.util.Vector records = new java.util.Vector();
    private boolean open = true;
    public static int opens, denied;

    private RecordStore(String name) { this.name = name; }

    private static void gate(String what) throws RecordStoreException {
        String m = System.getProperty("stub.rms");
        if ("deny".equals(m)) {
            denied++;
            throw new RecordStoreException("access denied: " + what);
        }
    }

    public static RecordStore openRecordStore(String name, boolean create) throws RecordStoreException {
        gate("open " + name);
        synchronized (STORES) {
            RecordStore rs = (RecordStore) STORES.get(name);
            if (rs == null) {
                if (!create) throw new RecordStoreNotFoundException("no store: " + name);
                rs = new RecordStore(name);
                STORES.put(name, rs);
            }
            opens++;
            return rs;
        }
    }

    public static void deleteRecordStore(String name) throws RecordStoreException {
        synchronized (STORES) { STORES.remove(name); }
    }

    public static String[] listRecordStores() { return null; }

    public int addRecord(byte[] data, int offset, int numBytes) {
        records.addElement(copy(data, offset, numBytes));
        return records.size();
    }

    public void setRecord(int id, byte[] data, int offset, int numBytes) throws RecordStoreException {
        if (id < 1 || id > records.size()) throw new InvalidRecordIDException("id " + id);
        records.setElementAt(copy(data, offset, numBytes), id - 1);
    }

    public byte[] getRecord(int id) throws RecordStoreException {
        if (id < 1 || id > records.size()) throw new InvalidRecordIDException("id " + id);
        byte[] r = (byte[]) records.elementAt(id - 1);
        return r == null ? new byte[0] : r;
    }

    public int getRecord(int id, byte[] buffer, int offset) throws RecordStoreException {
        byte[] r = getRecord(id);
        int n = r.length;
        if (buffer.length - offset < n) n = buffer.length - offset;
        System.arraycopy(r, 0, buffer, offset, n);
        return n;
    }

    public int getRecordSize(int id) throws RecordStoreException { return getRecord(id).length; }
    public int getNumRecords() { return records.size(); }
    public int getSize() { return 0; }
    public int getSizeAvailable() { return 65536; }
    public void deleteRecord(int id) throws RecordStoreException { }
    public int getNextRecordID() { return records.size() + 1; }
    public long getLastModified() { return 0; }
    public String getName() { return name; }
    public void closeRecordStore() { open = false; }
    public void addRecordListener(RecordListener l) { }
    public void removeRecordListener(RecordListener l) { }

    private static byte[] copy(byte[] data, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }
}
