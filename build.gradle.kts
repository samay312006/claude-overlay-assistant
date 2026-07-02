// Root project applies no plugins itself; see :core and :app.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
