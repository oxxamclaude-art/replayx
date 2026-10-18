package com.replayx.cli;

import com.replayx.store.CassetteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "validate", description = "Validate a cassette's integrity and format version.")
public class ValidateCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<cassette>", description = "Cassette directory")
    Path cassette;

    @Override
    public Integer call() {
        CassetteStore store = new CassetteStore();
        List<String> problems = store.validate(cassette);
        if (problems.isEmpty()) {
            try {
                int n = store.readExchanges(cassette).size();
                System.out.println("cassette OK: " + n + " exchange(s) in " + cassette);
            } catch (Exception e) {
                System.out.println("cassette OK: " + cassette);
            }
            return 0;
        }
        System.out.println("cassette INVALID: " + cassette);
        problems.forEach(p -> System.out.println("  ! " + p));
        return 1;
    }
}
