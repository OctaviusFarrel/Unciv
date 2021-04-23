package com.unciv.ui.pickerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextArea
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Json
import com.unciv.JsonParser
import com.unciv.MainMenuScreen
import com.unciv.models.ruleset.ModOptions
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.mainmenu.Github
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.thread

class ModManagementScreen: PickerScreen() {

    val modTable = Table().apply { defaults().pad(10f) }
    val downloadTable = Table().apply { defaults().pad(10f) }
    val modActionTable = Table().apply { defaults().pad(10f) }

    val amountPerPage = 30

    init {
        setDefaultCloseAction(MainMenuScreen())
        refreshModTable()

        topTable.add("Current mods".toLabel())
        topTable.add("Downloadable mods".toLabel())
//        topTable.add("Mod actions")
        topTable.row()


        topTable.add(ScrollPane(modTable)).height(scrollPane.height * 0.8f).pad(10f)

        downloadTable.add(getDownloadButton()).row()

        tryDownloadPage(1)

        topTable.add(ScrollPane(downloadTable)).height(scrollPane.height * 0.8f)//.size(downloadTable.width, topTable.height)

        topTable.add(modActionTable)
    }

    fun tryDownloadPage(pageNum: Int) {
        thread {
            val repoSearch: Github.RepoSearch
            try {
                repoSearch = Github.tryGetGithubReposWithTopic(amountPerPage, pageNum)!!
            } catch (ex: Exception) {
                Gdx.app.postRunnable {
                    ToastPopup("Could not download mod list", this)
                }
                return@thread
            }

            Gdx.app.postRunnable {
                for (repo in repoSearch.items) {
                    repo.name = repo.name.replace('-', ' ')
                    var downloadButtonText = repo.name

                    val existingMod = RulesetCache.values.firstOrNull { it.name == repo.name }
                    if (existingMod != null) {
                        if (existingMod.modOptions.lastUpdated != "" && existingMod.modOptions.lastUpdated != repo.updated_at)
                            downloadButtonText += " - {Updated}"
                    }

                    val downloadButton = downloadButtonText.toTextButton()

                    downloadButton.onClick {
                        descriptionLabel.setText(repo.description + "\n" + "[${repo.stargazers_count}]✯".tr())
                        removeRightSideClickListeners()
                        rightSideButton.enable()
                        rightSideButton.setText("Download [${repo.name}]".tr())
                        rightSideButton.onClick {
                            rightSideButton.setText("Downloading...".tr())
                            rightSideButton.disable()
                            downloadMod(repo) {
                                rightSideButton.setText("Downloaded!".tr())
                            }
                        }

                        modActionTable.clear()
                        addModInfoToActionTable(repo.html_url, repo.updated_at)
                    }
                    downloadTable.add(downloadButton).row()
                }
                if (repoSearch.items.size == amountPerPage) {
                    val nextPageButton = "Next page".toTextButton()
                    nextPageButton.onClick {
                        nextPageButton.remove()
                        tryDownloadPage(pageNum + 1)
                    }
                    downloadTable.add(nextPageButton).row()
                }
                downloadTable.pack()
                (downloadTable.parent as ScrollPane).actor = downloadTable
            }
        }
    }

    fun addModInfoToActionTable(repoUrl: String, updatedAt: String) {
        if (repoUrl != "") {
            modActionTable.add("Open Github page".toTextButton().onClick {
                Gdx.net.openURI(repoUrl)
            }).row()
        }

        if (updatedAt != "") {
            // Everything under java.time is from Java 8 onwards, meaning older phones that use Java 7 won't be able to handle it :/
            // So we're forced to use ancient Java 6 classes instead of the newer and nicer LocalDateTime.parse :(
            // Direct solution from https://stackoverflow.com/questions/2201925/converting-iso-8601-compliant-string-to-java-util-date
            val df2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US) // example: 2021-04-11T14:43:33Z
            val date = df2.parse(updatedAt)

            val updateString = "{Updated}: " +DateFormat.getDateInstance(DateFormat.SHORT).format(date)
            modActionTable.add(updateString.toLabel())
        }
    }

    fun getDownloadButton(): TextButton {
        val downloadButton = "Download mod from URL".toTextButton()
        downloadButton.onClick {
            val popup = Popup(this)
            val textArea = TextArea("https://github.com/...", skin)
            popup.add(textArea).width(stage.width / 2).row()
            val actualDownloadButton = "Download".toTextButton()
            actualDownloadButton.onClick {
                actualDownloadButton.setText("Downloading...".tr())
                actualDownloadButton.disable()
                downloadMod(Github.Repo().apply { html_url = textArea.text; default_branch = "master" }) { popup.close() }
            }
            popup.add(actualDownloadButton).row()
            popup.addCloseButton()
            popup.open()
        }
        return downloadButton
    }

    fun downloadMod(repo: Github.Repo, postAction: () -> Unit = {}) {
        thread { // to avoid ANRs - we've learnt our lesson from previous download-related actions
            try {
                val modFolder = Github.downloadAndExtract(repo.html_url, repo.default_branch,
                        Gdx.files.local("mods"))
                if (modFolder == null) return@thread
                // rewrite modOptions file
                val modOptionsFile = modFolder.child("jsons/ModOptions.json")
                val modOptions = if (modOptionsFile.exists()) JsonParser().getFromJson(ModOptions::class.java, modOptionsFile) else ModOptions()
                modOptions.modUrl = repo.html_url
                modOptions.lastUpdated = repo.updated_at
                Json().toJson(modOptions, modOptionsFile)
                Gdx.app.postRunnable {
                    ToastPopup("Downloaded!", this)
                    RulesetCache.loadRulesets()
                    refreshModTable()
                }
            } catch (ex: Exception) {
                Gdx.app.postRunnable {
                    ToastPopup("Could not download mod", this)
                }
            } finally {
                postAction()
            }
        }
    }

    fun refreshModActions(mod: Ruleset) {
        modActionTable.clear()
        val visualMods = game.settings.visualMods
        if (!visualMods.contains(mod.name))
            modActionTable.add("Enable as permanent visual mod".toTextButton().onClick {
                visualMods.add(mod.name)
                game.settings.save()
                ImageGetter.setNewRuleset(ImageGetter.ruleset)
                refreshModActions(mod)
            })
        else modActionTable.add("Disable as permanent visual mod".toTextButton().onClick {
            visualMods.remove(mod.name)
            game.settings.save()
            ImageGetter.setNewRuleset(ImageGetter.ruleset)
            refreshModActions(mod)
        })
        modActionTable.row()

        addModInfoToActionTable(mod.modOptions.modUrl, mod.modOptions.lastUpdated)
    }

    fun refreshModTable() {
        modTable.clear()
        val currentMods = RulesetCache.values.filter { it.name != "" }
        for (mod in currentMods) {
            val button = mod.name.toTextButton().onClick {
                refreshModActions(mod)
                rightSideButton.setText("Delete [${mod.name}]".tr())
                rightSideButton.enable()
                descriptionLabel.setText(mod.getSummary())
                removeRightSideClickListeners()
                rightSideButton.onClick {
                    YesNoPopup("Are you SURE you want to delete this mod?",
                            { deleteMod(mod) }, this).open()
                }
            }
            modTable.add(button).row()
        }
    }

    fun deleteMod(mod: Ruleset) {
        val modFileHandle = Gdx.files.local("mods").child(mod.name)
        if (modFileHandle.isDirectory) modFileHandle.deleteDirectory()
        else modFileHandle.delete()
        RulesetCache.loadRulesets()
        refreshModTable()
    }
}