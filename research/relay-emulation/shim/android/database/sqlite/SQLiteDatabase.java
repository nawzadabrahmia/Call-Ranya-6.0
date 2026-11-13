package android.database.sqlite;
import android.content.ContentValues; import android.database.Cursor; import java.sql.*; import java.util.*;
public final class SQLiteDatabase {
  public static final int CONFLICT_IGNORE = 4;
  public interface CursorFactory {}
  final Connection c;
  SQLiteDatabase(Connection c){this.c=c;}
  public synchronized void execSQL(String sql){ try(Statement s=c.createStatement()){s.execute(sql);}catch(SQLException e){throw new RuntimeException(e);} }
  public synchronized void execSQL(String sql, Object[] a){ try(PreparedStatement s=c.prepareStatement(sql)){for(int i=0;i<a.length;i++)s.setObject(i+1,a[i]); s.execute();}catch(SQLException e){throw new RuntimeException(e);} }
  public synchronized Cursor rawQuery(String sql, String[] a){
    try(PreparedStatement s=c.prepareStatement(sql)){ if(a!=null)for(int i=0;i<a.length;i++)s.setString(i+1,a[i]);
      ResultSet r=s.executeQuery(); int n=r.getMetaData().getColumnCount(); List<Object[]> rows=new ArrayList<>();
      while(r.next()){Object[] o=new Object[n]; for(int i=0;i<n;i++)o[i]=r.getObject(i+1); rows.add(o);} return new Cur(rows);
    }catch(SQLException e){throw new RuntimeException(e);} }
  public synchronized long insertWithOnConflict(String t, String nh, ContentValues v, int conflict){
    String cols=String.join(",",v.m.keySet()); String q=String.join(",",Collections.nCopies(v.m.size(),"?"));
    try(PreparedStatement s=c.prepareStatement("INSERT "+(conflict==CONFLICT_IGNORE?"OR IGNORE ":"")+"INTO "+t+"("+cols+") VALUES("+q+")")){
      int i=1; for(Object o: v.m.values()) s.setObject(i++,o); return s.executeUpdate(); }catch(SQLException e){throw new RuntimeException(e);} }
  public long insert(String t, String nh, ContentValues v){ return insertWithOnConflict(t,nh,v,0); }
  public synchronized int delete(String t, String w, String[] a){
    try(PreparedStatement s=c.prepareStatement("DELETE FROM "+t+(w!=null?" WHERE "+w:""))){ if(a!=null)for(int i=0;i<a.length;i++)s.setString(i+1,a[i]); return s.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);} }
  static final class Cur implements Cursor { final List<Object[]> rows; int p=-1; Cur(List<Object[]> r){rows=r;}
    public boolean moveToFirst(){p=0;return !rows.isEmpty();} public boolean moveToNext(){p++;return p<rows.size();}
    Object v(int i){return rows.get(p)[i];}
    public String getString(int i){Object o=v(i);return o==null?null:o.toString();}
    public int getInt(int i){Object o=v(i);return o==null?0:((Number)o).intValue();}
    public long getLong(int i){Object o=v(i);return o==null?0:((Number)o).longValue();}
    public double getDouble(int i){Object o=v(i);return o==null?0:((Number)o).doubleValue();}
    public boolean isNull(int i){return v(i)==null;} public int getColumnIndex(String n){return -1;} public void close(){} }
}
