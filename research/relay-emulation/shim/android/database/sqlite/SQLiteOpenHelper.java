package android.database.sqlite;
import android.content.Context; import java.io.File; import java.sql.*;
public abstract class SQLiteOpenHelper {
  private SQLiteDatabase db; private final String path;
  public SQLiteOpenHelper(Context ctx, String name, SQLiteDatabase.CursorFactory f, int v){ path=new File(ctx.getFilesDir(),name).getPath(); }
  public synchronized SQLiteDatabase getWritableDatabase(){
    if(db==null){ try{ boolean fresh=!new File(path).exists(); db=new SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:"+path)); if(fresh) onCreate(db);}catch(SQLException e){throw new RuntimeException(e);} }
    return db; }
  public SQLiteDatabase getReadableDatabase(){ return getWritableDatabase(); }
  public abstract void onCreate(SQLiteDatabase db); public abstract void onUpgrade(SQLiteDatabase db, int o, int n);
}
