import java.util.*; import java.util.zip.*; import java.io.*;
import com.android.tools.smali.dexlib2.*; import com.android.tools.smali.dexlib2.dexbacked.*;
import com.android.tools.smali.dexlib2.iface.instruction.*; import com.android.tools.smali.dexlib2.iface.reference.*;
public class AuditCombined {
 public static void main(String[] args)throws Exception {
  int binds=0, profileClicks=0, queueEnable=0, queueUI=0; Set<String> settings=new TreeSet<>(); Set<String> hooks=new TreeSet<>();
  try(ZipFile z=new ZipFile(args[0])) {var entries=z.entries();while(entries.hasMoreElements()){
   var e=entries.nextElement();if(!e.getName().endsWith(".dex"))continue;
   var dex=DexBackedDexFile.fromInputStream(Opcodes.getDefault(),new BufferedInputStream(z.getInputStream(e)));
   for(var c:dex.getClasses())for(var m:c.getMethods()) {
    var impl=m.getImplementation();if(impl==null)continue;
    if(!(c.getType().equals("LX/0DNO;")||c.getType().contains("UserDetailFragment;")||c.getType().equals("LX/0RLB;")||c.getType().contains("instagram/settings/SettingsStatus;")||c.getType().contains("instagram/settings/preference/ScreenBuilder;")))continue;
    for(var i:impl.getInstructions())if(i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference ref){
     if(ref.getDefiningClass().contains("batchDownload/BatchDownloadManager;")){hooks.add(c.getType()+" "+m.getName()+" -> "+ref.getName());if(ref.getName().equals("bind"))binds++;if(ref.getName().equals("profileClick"))profileClicks++;}
     if(ref.getDefiningClass().contains("downloader/MediaDownloader;")&&ref.getName().equals("enableInstagramQueue"))queueEnable++;
     if(ref.getDefiningClass().contains("batchDownload/DownloadQueueDialog;")&&ref.getName().equals("show"))queueUI++;
     if(m.getName().equals("load")&&ref.getDefiningClass().contains("instagram/settings/SettingsStatus;"))settings.add(ref.getName());
    }
   }
  }}
  for(String s:hooks)System.out.println(s);
  System.out.println("SETTINGS_FLAGS "+settings);
  if(binds!=1||profileClicks!=1||!settings.containsAll(Arrays.asList("viewStoriesAnonymously","viewLiveAnonymously","disableAds","downloadMedia")))throw new AssertionError("Missing/duplicate grid hook or original settings initialization");
  if(hooks.stream().noneMatch(s->s.contains("-> attachProfile"))||hooks.stream().noneMatch(s->s.contains("-> attach")))throw new AssertionError("Missing screen hook");
  if(queueEnable!=1||queueUI<1||hooks.stream().noneMatch(s->s.contains("AeX -> profileToolbar")))throw new AssertionError("Missing queue settings or profile toolbar hook");
  System.out.println("PASS: one shared grid binder, one profile listener wrapper, both lifecycle hooks, original settings initialization");
 }
}

