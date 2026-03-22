from pythonforandroid.recipe import PythonRecipe


class SympyRecipe(PythonRecipe):
    version = "1.1.1"
    url = "https://github.com/sympy/sympy/releases/download/sympy-{version}/sympy-{version}.tar.gz"

    depends = ["mpmath"]

    # The hostpython build tree does not add native-build/Lib/site-packages to
    # sys.path by default, so sympy must use the hostpython environment wiring
    # from PythonRecipe.get_recipe_env() to import mpmath during setup.py.
    call_hostpython_via_targetpython = False


recipe = SympyRecipe()