from pythonforandroid.recipe import PythonRecipe


class MpmathRecipe(PythonRecipe):
    version = "1.3.0"
    url = "https://pypi.python.org/packages/source/m/mpmath/mpmath-{version}.tar.gz"

    depends = ["setuptools"]

    call_hostpython_via_targetpython = False
    install_in_hostpython = True


recipe = MpmathRecipe()
