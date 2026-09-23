import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 从 dex 中剔除指定的类（用于把外部通用 spider jar 合并进本地 TvDy.jar 时去掉重名类）。
 * 用法: java DexFilter <in.dex> <out.dex> <type1> <type2> ...
 */
public class DexFilter {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DexFilter <in.dex> <out.dex> [type...]");
            System.exit(1);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        Set<String> exclude = new HashSet<String>();
        for (int i = 2; i < args.length; i++) exclude.add(args[i]);

        DexFile dexFile = DexFileFactory.loadDexFile(in, Opcodes.forApi(19));
        DexPool pool = new DexPool(Opcodes.forApi(19));
        int total = 0, kept = 0, dropped = 0;
        for (ClassDef cd : dexFile.getClasses()) {
            total++;
            if (exclude.contains(cd.getType())) {
                dropped++;
                continue;
            }
            pool.internClass(cd);
            kept++;
        }
        FileDataStore store = new FileDataStore(out);
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        System.out.println("total=" + total + " kept=" + kept + " dropped=" + dropped);
    }
}
