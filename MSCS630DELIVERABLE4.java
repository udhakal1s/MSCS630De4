// MSCS630DELIVERABLE4.java
// Integrated shell: D1 (basic shell) + D2 (scheduling) + D3 (memory & sync) + D4 (piping & security)
// Author: generated for user
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;  // ✅ added import for FileTime

public class MSCS630DELIVERABLE4 {
    // --- User & Permissions ---
    static class User { String name; String pass; String role; User(String n,String p,String r){name=n;pass=p;role=r;} }
    static Map<String,User> users = new HashMap<>();
    static User currentUser = null;
    static Map<String,String> filePerms = new HashMap<>(); // filename -> "rwx"

    // --- Jobs / Process simulation ---
    static int nextPid = 1000;
    static class Proc {
        int pid; String cmd; Thread thread; boolean background; volatile boolean stopped=false;
        Proc(String c, Runnable r, boolean bg){ pid=nextPid++; cmd=c; background=bg; thread=new Thread(r); }
    }
    static Map<Integer,Proc> procs = new ConcurrentHashMap<>();
    static List<Integer> jobOrder = Collections.synchronizedList(new ArrayList<>());

    // --- Scheduler for D2 ---
    static class SimProcess {
        int pid; int burst; int remaining; int priority;
        SimProcess(int pid,int b,int pr){this.pid=pid;burst=b;remaining=b;priority=pr;}
    }
    static List<SimProcess> ready = Collections.synchronizedList(new LinkedList<>());
    static ExecutorService schedulerExec = Executors.newSingleThreadExecutor();

    // --- Memory manager for D3 ---
    static class Page { int pid, pnum; Page(int a,int b){pid=a;pnum=b;} public String toString(){return "P"+pid+":"+pnum;} }
    static class Frame { Page page; long lastUsed; Frame(Page p){page=p; touch();} void touch(){lastUsed=System.nanoTime();} }
    static List<Frame> frames = Collections.synchronizedList(new ArrayList<>());
    static int FRAME_COUNT = 4;
    static Map<Integer,Set<Integer>> procPages = new HashMap<>();
    static int pageFaults = 0;

    // --- Synchronization demo (Producer-Consumer) ---
    static BlockingQueue<Integer> buffer = new ArrayBlockingQueue<>(5);
    static volatile boolean pcRunning = false;

    // --- Main ---
    public static void main(String[] args) throws Exception {
        users.put("admin", new User("admin","admin123","admin"));
        users.put("user", new User("user","user123","standard"));
        filePerms.put("/etc/system.cfg","r--");
        filePerms.put("data.txt","rw-");
        if(!authenticate()) return;
        System.out.println("Welcome " + currentUser.name + " ("+currentUser.role+") to MSCS630 Shell. Type help.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while(true){
            System.out.print(currentUser.name + "@mscs630> ");
            line = in.readLine();
            if(line==null) break;
            line = line.trim();
            if(line.isEmpty()) continue;
            try {
                String output = handlePipeline(line);
                if(output != null && !output.isEmpty()) System.out.print(output);
            } catch(Exception e){
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    // --- Authentication ---
    static boolean authenticate() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Username: ");
        String u = in.readLine();
        System.out.print("Password: ");
        String p = in.readLine();
        User user = users.get(u);
        if(user!=null && user.pass.equals(p)){ currentUser=user; return true; }
        System.out.println("Auth failed."); return false;
    }

    // --- Pipeline handler ---
    static String handlePipeline(String line) throws Exception {
        String[] parts = line.split("\\|");
        String input = "";
        for(int i=0;i<parts.length;i++){
            String cmd = parts[i].trim();
            boolean bg = cmd.endsWith("&");
            if(bg) cmd = cmd.substring(0, cmd.length()-1).trim();
            input = executeCommand(cmd, input, bg);
            if(bg) return "";
        }
        return input;
    }

    // --- Execute command ---
    static String executeCommand(String line, String input, boolean background) throws Exception {
        String[] toks = splitArgs(line);
        if(toks.length==0) return "";
        String cmd = toks[0];
        switch(cmd){
            case "exit": System.exit(0); return "";
            case "cd": return builtin_cd(toks);
            case "pwd": return builtin_pwd();
            case "echo": return join(toks,1) + "\n";
            case "clear": for(int i=0;i<50;i++) System.out.println(); return "";
            case "ls": return builtin_ls();
            case "cat": return builtin_cat(toks, input);
            case "mkdir": return builtin_mkdir(toks);
            case "rmdir": return builtin_rmdir(toks);
            case "rm": return builtin_rm(toks);
            case "touch": return builtin_touch(toks);
            case "kill": return builtin_kill(toks);
            case "jobs": return builtin_jobs();
            case "fg": return builtin_fg(toks);
            case "bg": return builtin_bg(toks);
            case "help": return helpText();
            case "addsim": return cmd_addsim(toks);
            case "runs_rr": return cmd_run_rr(toks);
            case "runs_prio": return cmd_run_prio(toks);
            case "mem_info": return cmd_mem_info();
            case "access_page": return cmd_access_page(toks);
            case "setframe": return cmd_setframe(toks);
            case "start_pc": return cmd_start_pc();
            case "stop_pc": return cmd_stop_pc();
            case "showusers": return showUsers();
            case "su": return cmd_su(toks);
            default:
                Runnable r = () -> {
                    try { Thread.sleep(1000); System.out.println("Executed: " + line); } catch(Exception e){}
                };
                Proc p = new Proc(line, r, background);
                procs.put(p.pid,p); jobOrder.add(p.pid);
                if(background){ p.thread.start(); return "Started bg pid " + p.pid + "\n"; }
                else { p.thread.run(); procs.remove(p.pid); jobOrder.remove((Integer)p.pid); return ""; }
        }
    }

    // --- Builtins ---
    static String builtin_cd(String[] toks){
        if(toks.length<2) return "";
        Path p = Paths.get(toks[1]);
        try { System.setProperty("user.dir", p.toAbsolutePath().toString()); return ""; }
        catch(Exception e){ return "cd error\n"; }
    }
    static String builtin_pwd(){ return System.getProperty("user.dir") + "\n"; }
    static String builtin_ls(){
        try{
            File dir = new File(System.getProperty("user.dir"));
            StringBuilder sb=new StringBuilder();
            for(String f: dir.list()) sb.append(f).append("\n");
            return sb.toString();
        }catch(Exception e){ return "ls error\n"; }
    }
    static String builtin_cat(String[] toks, String input){
        if(toks.length>=2){
            String fn=toks[1];
            if(!checkPerm(fn,'r')) return "Permission denied\n";
            try{ return new String(Files.readAllBytes(Paths.get(fn))) + "\n"; } catch(Exception e){ return "cat error\n"; }
        } else return input;
    }
    static String builtin_mkdir(String[] t){ if(t.length<2) return ""; try{ Files.createDirectory(Paths.get(t[1])); return ""; }catch(Exception e){return "mkdir error\n";}}
    static String builtin_rmdir(String[] t){ if(t.length<2) return ""; try{ Files.delete(Paths.get(t[1])); return ""; }catch(Exception e){return "rmdir error\n";}}
    static String builtin_rm(String[] t){ if(t.length<2) return ""; if(!checkPerm(t[1],'w')) return "Permission denied\n"; try{ Files.delete(Paths.get(t[1])); return ""; }catch(Exception e){return "rm error\n";}}
    static String builtin_touch(String[] t){ 
        if(t.length<2) return ""; 
        try{ 
            Path p=Paths.get(t[1]); 
            if(!Files.exists(p)) Files.createFile(p); 
            else Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis())); 
            return ""; 
        }catch(Exception e){return "touch error\n";} 
    }
    static String builtin_kill(String[] t){ if(t.length<2) return "Usage\n"; try{ int pid=Integer.parseInt(t[1]); Proc p=procs.get(pid); if(p!=null){ p.thread.interrupt(); procs.remove(pid); jobOrder.remove((Integer)pid); return "Killed " + pid + "\n"; } return "No such pid\n"; }catch(Exception e){return "kill error\n";}}
    static String builtin_jobs(){ StringBuilder sb=new StringBuilder(); for(int pid: jobOrder){ Proc p=procs.get(pid); if(p!=null) sb.append("[").append(pid).append("] ").append(p.cmd).append("\n"); } return sb.toString(); }
    static String builtin_fg(String[] t){ if(t.length<2) return "Usage\n"; int jid=Integer.parseInt(t[1]); Proc p=procs.get(jid); if(p==null) return "No such job\n"; try{ p.thread.join(); procs.remove(jid); jobOrder.remove((Integer)jid); }catch(Exception e){} return ""; }
    static String builtin_bg(String[] t){ if(t.length<2) return "Usage\n"; int jid=Integer.parseInt(t[1]); Proc p=procs.get(jid); if(p==null) return "No such job\n"; p.thread.start(); return ""; }

    static String helpText(){
        return "Builtins: cd pwd exit echo clear ls cat mkdir rmdir rm touch kill jobs fg bg\n" +
               "Scheduling: addsim <name> <burst> <priority> | runs_rr <quantum_ms> | runs_prio\n" +
               "Memory: setframe <n> mem_info access_page <pid> <pageno> \n" +
               "Sync: start_pc stop_pc\n" +
               "Security: showusers su\n";
    }

    // --- Utils ---
    static String[] splitArgs(String line){
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inq=false;
        for(char c: line.toCharArray()){
            if(c=='"') inq=!inq;
            else if(Character.isWhitespace(c) && !inq){ if(cur.length()>0){out.add(cur.toString());cur.setLength(0);} }
            else cur.append(c);
        }
        if(cur.length()>0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
    static String join(String[] a,int s){ if(a.length<=s) return ""; StringBuilder b=new StringBuilder(); for(int i=s;i<a.length;i++){ if(i> s) b.append(" "); b.append(a[i]); } return b.toString(); }

    // --- Scheduling ---
    static String cmd_addsim(String[] t){
        if(t.length<4) return "Usage: addsim <name> <burst> <priority>\n";
        int burst = Integer.parseInt(t[2]);
        int pr = Integer.parseInt(t[3]);
        SimProcess sp = new SimProcess(nextPid++, burst, pr);
        ready.add(sp);
        return "Added sim pid " + sp.pid + " burst=" + burst + " pr=" + pr + "\n";
    }
    static String cmd_run_rr(String[] t){
        if(ready.isEmpty()) return "No processes\n";
        int quantumVal = 500;
        if(t.length>=2) quantumVal = Integer.parseInt(t[1]);
        final int quantum = quantumVal;  // ✅ effectively final
        schedulerExec.submit(() -> {
            try{
                Queue<SimProcess> q = new LinkedList<>(ready);
                ready.clear();
                while(!q.isEmpty()){
                    SimProcess p = q.poll();
                    int run = Math.min(quantum, p.remaining);
                    System.out.println("RR Running pid " + p.pid + " for " + run + "ms");
                    Thread.sleep(run);
                    p.remaining -= run;
                    if(p.remaining>0) q.add(p); else System.out.println("Process " + p.pid + " finished");
                }
            }catch(Exception e){ System.out.println("RR scheduler error"); }
        });
        return "RR scheduled\n";
    }
    static String cmd_run_prio(String[] t){
        if(ready.isEmpty()) return "No processes\n";
        schedulerExec.submit(() -> {
            try{
                List<SimProcess> list = new ArrayList<>(ready);
                ready.clear();
                while(!list.isEmpty()){
                    list.sort((a,b)->Integer.compare(b.priority,a.priority));
                    SimProcess p = list.remove(0);
                    System.out.println("PRIO Running pid " + p.pid + " for 100ms slice");
                    Thread.sleep(100);
                    p.remaining -= 100;
                    if(p.remaining>0) list.add(p); else System.out.println("Process " + p.pid + " finished");
                }
            }catch(Exception e){ System.out.println("PRIO scheduler error"); }
        });
        return "PRIO scheduled\n";
    }

    // --- Memory ---
    static String cmd_setframe(String[] t){
        if(t.length<2) return "Usage\n";
        FRAME_COUNT = Integer.parseInt(t[1]);
        frames.clear();
        for(int i=0;i<FRAME_COUNT;i++) frames.add(new Frame(null));
        return "Frames set to " + FRAME_COUNT + "\n";
    }
    static String cmd_mem_info(){
        StringBuilder sb = new StringBuilder();
        sb.append("Frames: ").append(FRAME_COUNT).append("\n");
        for(int i=0;i<frames.size();i++){
            Frame f = frames.get(i);
            sb.append(i).append(": ").append(f.page==null?"-":f.page.toString()).append("\n");
        }
        sb.append("PageFaults: ").append(pageFaults).append("\n");
        return sb.toString();
    }
    static synchronized String cmd_access_page(String[] t){
        if(t.length<3) return "Usage: access_page <pid> <pageno>\n";
        int pid = Integer.parseInt(t[1]); int pg = Integer.parseInt(t[2]);
        for(Frame f: frames) if(f.page!=null && f.page.pid==pid && f.page.pnum==pg){ f.touch(); return "Page hit\n"; }
        pageFaults++;
        Page newp = new Page(pid,pg);
        Frame victim = null;
        for(Frame f: frames) if(f.page==null){ victim = f; break; }
        if(victim==null){
            victim = frames.get(0);
            for(Frame f: frames) if(f.lastUsed < victim.lastUsed) victim = f;
        }
        victim.page = newp; victim.touch();
        procPages.computeIfAbsent(pid, k->new HashSet<>()).add(pg);
        return "Page loaded (fault)\n";
    }

    // --- Producer-Consumer ---
    static String cmd_start_pc(){
        if(pcRunning) return "Already running\n";
        pcRunning = true;
        Thread prod = new Thread(() -> {
            int i=0;
            try{
                while(pcRunning){
                    buffer.put(i++);
                    System.out.println("Produced " + (i-1));
                    Thread.sleep(300);
                }
            }catch(Exception e){}
        });
        Thread cons = new Thread(() -> {
            try{
                while(pcRunning){
                    Integer v = buffer.take();
                    System.out.println("Consumed " + v);
                    Thread.sleep(500);
                }
            }catch(Exception e){}
        });
        prod.start(); cons.start();
        return "Producer-Consumer started\n";
    }
    static String cmd_stop_pc(){
        pcRunning = false; return "Stopped PC\n";
    }

    // --- Security ---
    static String showUsers(){
        StringBuilder sb=new StringBuilder();
        for(User u: users.values()) sb.append(u.name).append(" (").append(u.role).append(")\n");
        return sb.toString();
    }
    static String cmd_su(String[] t){
        if(t.length<3) return "Usage: su <user> <pass>\n";
        User u = users.get(t[1]);
        if(u!=null && u.pass.equals(t[2])){ currentUser = u; return "Switched to " + u.name + "\n"; }
        return "Auth failed\n";
    }
    static boolean checkPerm(String filename, char want){
        if(currentUser.role.equals("admin")) return true;
        String p = filePerms.getOrDefault(filename, "rwx");
        if(want=='r') return p.indexOf('r')>=0;
        if(want=='w') return p.indexOf('w')>=0;
        if(want=='x') return p.indexOf('x')>=0;
        return false;
    }
}

