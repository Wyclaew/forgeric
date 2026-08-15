package dev.forgeric.installer.ui;

import dev.forgeric.installer.ForgericInstaller;
import dev.forgeric.installer.core.InstallLog;
import dev.forgeric.installer.core.Payload;
import dev.forgeric.installer.core.Platform;
import dev.forgeric.installer.profile.VersionProfile;
import dev.forgeric.installer.target.ModsFolderTarget;
import dev.forgeric.installer.target.PrismTarget;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The window shown when the jar is double-clicked.
 *
 * <p>Kept intentionally plain — Swing is here because it is the only toolkit guaranteed to be
 * present on a machine that can run Minecraft, not because this needs to be pretty. Install
 * work happens off the event thread so the window keeps repainting.
 */
public final class InstallerWindow {
    private InstallerWindow() {}

    public static void launch() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // The cross-platform look and feel is a fine fallback.
        }
        SwingUtilities.invokeLater(InstallerWindow::build);
    }

    private static void build() {
        JFrame frame = new JFrame("Forgeric Installer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JComboBox<String> versionBox = new JComboBox<>();
        List<String> versions = VersionProfile.listBundled();
        if (versions.isEmpty()) {
            versionBox.addItem(ForgericInstaller.DEFAULT_MINECRAFT);
        } else {
            versions.forEach(versionBox::addItem);
            versionBox.setSelectedItem(ForgericInstaller.DEFAULT_MINECRAFT);
        }

        JRadioButton newPrism = new JRadioButton("Create a new Prism Launcher instance", true);
        JRadioButton existingFolder = new JRadioButton("Install into an existing folder");
        ButtonGroup group = new ButtonGroup();
        group.add(newPrism);
        group.add(existingFolder);

        JTextField instanceName = new JTextField("Forgeric " + ForgericInstaller.DEFAULT_MINECRAFT);
        JTextField folderField = new JTextField();
        folderField.setEnabled(false);
        JButton browse = new JButton("Browse…");
        browse.setEnabled(false);

        List<Path> prismDirs = Platform.prismDataDirs();
        JLabel prismStatus = new JLabel(prismDirs.isEmpty()
                ? "Prism Launcher not detected — use the folder option instead."
                : "Prism found at " + prismDirs.getFirst());
        prismStatus.setFont(prismStatus.getFont().deriveFont(Font.PLAIN, 11f));

        newPrism.setEnabled(!prismDirs.isEmpty());
        if (prismDirs.isEmpty()) {
            existingFolder.setSelected(true);
            folderField.setEnabled(true);
            browse.setEnabled(true);
        }

        Runnable syncEnabled = () -> {
            boolean folder = existingFolder.isSelected();
            folderField.setEnabled(folder);
            browse.setEnabled(folder);
            instanceName.setEnabled(!folder);
        };
        newPrism.addActionListener(e -> syncEnabled.run());
        existingFolder.addActionListener(e -> syncEnabled.run());

        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select the game directory (the one containing 'mods')");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                folderField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JTextArea logArea = new JTextArea(10, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton install = new JButton("Install");
        install.addActionListener(e -> {
            install.setEnabled(false);
            logArea.setText("");
            String version = (String) versionBox.getSelectedItem();
            boolean toFolder = existingFolder.isSelected();
            String name = instanceName.getText().trim();
            String folder = folderField.getText().trim();
            Path prismDir = prismDirs.isEmpty() ? null : prismDirs.getFirst();

            new Thread(() -> {
                runInstall(version, toFolder, name, folder, prismDir, logArea);
                SwingUtilities.invokeLater(() -> install.setEnabled(true));
            }, "forgeric-install").start();
        });

        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        options.add(labeled("Minecraft version", versionBox));
        options.add(Box.createVerticalStrut(10));
        options.add(newPrism);
        options.add(labeled("Instance name", instanceName));
        options.add(prismStatus);
        options.add(Box.createVerticalStrut(10));
        options.add(existingFolder);

        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folderField, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);
        options.add(folderRow);
        options.add(Box.createVerticalStrut(12));
        options.add(install);

        frame.setLayout(new BorderLayout());
        frame.add(options, BorderLayout.NORTH);
        frame.add(new JScrollPane(logArea), BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(660, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JPanel labeled(String label, java.awt.Component field) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        JLabel jLabel = new JLabel(label);
        jLabel.setPreferredSize(new Dimension(120, 24));
        panel.add(jLabel, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private static void runInstall(String version, boolean toFolder, String instanceName,
                                   String folder, Path prismDir, JTextArea logArea) {
        InstallLog log = new InstallLog(line ->
                SwingUtilities.invokeLater(() -> logArea.append(line + "\n")));

        try {
            VersionProfile profile = VersionProfile.loadBundled(version);
            if (profile.isObfuscated()) {
                log.error("Minecraft " + version + " ships obfuscated and needs a runtime remapper "
                        + "Forgeric does not have yet.");
                return;
            }

            Path loaderJar = Payload.extractLoaderJar();
            log.info("Forgeric " + profile.forgericVersion() + " (" + profile.status() + ")");
            log.info("Minecraft " + profile.minecraft() + " / NeoForge " + profile.neoForgeVersion());
            log.info("");

            if (toFolder) {
                if (folder.isEmpty()) {
                    log.error("Choose a folder first.");
                    return;
                }
                Path path = Path.of(folder);
                if (!Files.isDirectory(path)) {
                    log.error(path + " is not a folder.");
                    return;
                }
                ModsFolderTarget.install(path, loaderJar, log);
            } else {
                if (instanceName.isEmpty()) {
                    log.error("Enter an instance name.");
                    return;
                }
                if (prismDir == null) {
                    log.error("Prism Launcher was not detected.");
                    return;
                }
                PrismTarget.createInstance(prismDir, instanceName, profile, loaderJar, log);
            }

            log.info("");
            log.info("Done. Put Forge and Fabric mods together in the mods folder.");
            if (!profile.fabricApiSupported()) {
                log.warn("Fabric mods depending on fabric-api will not load yet.");
            }
        } catch (Exception e) {
            log.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
