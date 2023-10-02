/**
 * @Author: turk
 * @Description: Sintaksni analizator.
 */

package compiler.parser;

import static compiler.lexer.TokenType.*;
import static common.RequireNonNull.requireNonNull;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import common.Report;
import compiler.lexer.Position;
import compiler.lexer.Symbol;
import compiler.lexer.TokenType;
import compiler.parser.ast.Ast;
import compiler.parser.ast.def.*;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.Array;
import compiler.parser.ast.type.Atom;
import compiler.parser.ast.type.Type;
import compiler.parser.ast.type.TypeName;

public class Parser {
    /**
     * Seznam leksikalnih simbolov.
     */
    private final List<Symbol> symbols;

    /**
     * Ciljni tok, kamor izpisujemo produkcije. Če produkcij ne želimo izpisovati,
     * vrednost opcijske spremenljivke nastavimo na Optional.empty().
     */
    private final Optional<PrintStream> productionsOutputStream;

    public Parser(List<Symbol> symbols, Optional<PrintStream> productionsOutputStream) {
        requireNonNull(symbols, productionsOutputStream);
        this.symbols = symbols;
        this.productionsOutputStream = productionsOutputStream;
    }

    /**
     * Izvedi sintaksno analizo.
     */
    public Ast parse() {
        var ast = parseSource();
        if (symbols.get(index).tokenType != EOF && symbols.get(index + 1).tokenType == EOF){
            error();

        }
        return ast;
    }


    public void error(){
        Report.error("Napaka v sintaksi!");
        System.exit(99);
    }

    public Symbol getter(int i){
        return symbols.get(i);
    }

    public int getStartLine(int i){
        return getter(i).position.start.line;
    }

    public int getStartColumn(int i){
        return getter(i).position.start.column;
    }

    public int getEndLine(int i){
        return getter(i).position.end.line;
    }

    public int getEndColumn(int i){
        return getter(i).position.end.column;
    }

    int index = 0;
    public TokenType peek(){
        return symbols.get(index).tokenType;
    }
    public void skip(){
        index++;
    }

    private Ast parseSource() {
        // TODO: - rekurzivno spuščanje
        if (symbols.size() == 1) {
            error();
        } else {
            dump("source -> definitions");
        }

        return parseDefinitions();
    }

    private Defs parseDefinitions(){
        dump("definitions -> definition definitions2");
        int startl = getter(index).position.start.line;
        int startcol = getter(index).position.start.column;
        List<Def> def = new ArrayList<>();
        def.add(parseDefinition());
        var defs2 = parseDefinitions2(def);
        return new Defs(new Position(startl,startcol,
                defs2.get(def.size() - 1).position.end.line, defs2.get(def.size() - 1).position.end.column),
                def);
    }

    private Def parseDefinition(){
        int startl = getStartLine(index);
        int startcol = getStartColumn(index);

        if (peek() == KW_TYP){
            dump("definition -> type_definition");
            skip();
            return parseType_definition(startl, startcol);
        } else if (peek() == KW_FUN) {
            dump("definition -> function_definition");
            skip();
            return parseFunction_definition(startl, startcol);
        } else if (peek() == KW_VAR) {
            dump("definition -> variable_definition");
            skip();
            return parseVariable_definition(startl, startcol);
        }
        else{
            error(); return null;
        }
    }

    private VarDef parseVariable_definition(int startl, int startcol){
        dump("variable_definition -> var identifier : type");
        if (peek() == IDENTIFIER){
            String name = getter(index).lexeme;
            skip();
            if (peek() == OP_COLON){
                skip();
                var type = parseType();
                return new VarDef(new Position(startl, startcol, type.position.end.line, type.position.end.column),
                        name, type);
            } else error();
        } else error(); return null;
    }
    private FunDef parseFunction_definition(int startl, int startcol){
        dump("function_definition -> fun identifier ( parameters ) : type = expression");
        if (peek() == IDENTIFIER){
            String name = getter(index).lexeme;
            skip();
            if (peek() == OP_LPARENT){
                skip();
                List<FunDef.Parameter> p = new ArrayList<>();
                var parameters = parseParameters(p);
                if (peek() == OP_RPARENT){
                    skip();
                    if (peek() == OP_COLON){
                        skip();
                        var type = parseType();
                        if (peek() == OP_ASSIGN){
                            skip();
                            var expr = parseExpression();
                            return new FunDef(new Position(startl, startcol, getEndLine(index-1),
                                    getEndColumn(index-1)), name, parameters, type, expr);
                        } else error();
                    } else error();
                } else error();
            } else error();
        } else error(); return null;
    }

    private Expr parseExpression(){
        dump("expression -> logical_ior_expression expression2");
        var leftExpression = parseLogical_ior_expression();
        return parseExpression2(leftExpression);
    }

    private Expr parseExpression2(Expr leftExpression){
        if (peek() == OP_LBRACE){
            skip();
            dump("expression2 -> { WHERE definitions }");
            if (peek() == KW_WHERE){
                skip();
                var defs = parseDefinitions();
                if (peek() == OP_RBRACE){
                    skip();
                    return new Where(new Position(leftExpression.position.start.line,
                            leftExpression.position.start.column, getEndLine(index-1),
                            getEndColumn(index-1)), leftExpression, defs);
                } else error(); return null;
            } else error(); return null;
        }
        else{
            dump("expression2 -> e");
            return leftExpression;
        }
    }

    private Expr parseLogical_ior_expression(){
        dump("logical_ior_expression -> logical_and_expression logical_ior_expression2");
        var left = parseLogical_and_expression();
        return parseLogical_ior_expression2(left);
    }

    private Expr parseLogical_ior_expression2(Expr leftExpression){
        if (peek() == OP_OR){
            dump("logical_ior_expression2 -> | logical_and_expression logical_ior_expression2");
            skip();
            var right = parseLogical_and_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.OR, right);
            return parseLogical_ior_expression2(binary);
        }
        else{
            dump("logical_ior_expression2 -> e");
            return leftExpression;
        }
    }
    private Expr parseLogical_and_expression(){
        dump("logical_and_expression -> compare_expression logical_and_expression2");
        var left = parseCompare_expression();
        return parseLogical_and_expression2(left);
    }

    private Expr parseLogical_and_expression2(Expr leftExpression){
        if (peek() == OP_AND){
            dump("logical_and_expression2 -> & compare_expression logical_and_expression2");
            skip();
            var right = parseCompare_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.AND, right);
            return parseLogical_and_expression2(binary);
        }
        else{
            dump("logical_and_expression2 -> e");
            return leftExpression;
        }
    }

    private Expr parseCompare_expression(){
        dump("compare_expression -> additive_expression compare_expression2");
        var left = parseAdditive_expression();
        return parseCompare_expression2(left);
    }

    private Expr parseCompare_expression2(Expr leftExpression){
        if (peek() == OP_EQ){
            dump("compare_expression2 -> == additive_expression");
            skip();
            var right = parseAdditive_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.EQ, right);
            return binary;
        } else if (peek() == OP_NEQ) {
            dump("compare_expression2 -> != additive_expression");
            skip();
            var right = parseAdditive_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.NEQ, right);
            return binary;
        } else if (peek() == OP_LEQ) {
            dump("compare_expression2 -> <= additive_expression");
            skip();
            var right = parseAdditive_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.LEQ, right);
            return binary;
        } else if (peek() == OP_GEQ) {
            dump("compare_expression2 -> >= additive_expression");
            skip();
            var right = parseAdditive_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.GEQ, right);
            return binary;
        } else if (peek()  == OP_LT) {
            dump("compare_expression2 -> < additive_expression");
            skip();
            var right = parseAdditive_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.LT, right);
            return binary;
        } else if (peek() == OP_GT) {
            dump("compare_expression2 -> > additive_expression");
            skip();
            var right = parseAdditive_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column), leftExpression, Binary.Operator.GT, right);
            return binary;
        }
        else{
            dump("compare_expression2 -> e");
            return leftExpression;
        }
    }

    private Expr parseAdditive_expression(){
        dump("additive_expression -> multiplicative_expression additive_expression2");
        var left = parseMultiplicative_expression();
        return parseAdditive_expression2(left);
    }

    private Expr parseAdditive_expression2(Expr leftExpression){
        if (peek() == OP_ADD){
            dump("additive_expression2 -> + multiplicative_expression additive_expression2");
            skip();
            var right = parseMultiplicative_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.ADD, right);
            return parseAdditive_expression2(binary);
        } else if (peek() == OP_SUB) {
            dump("additive_expression2 -> - multiplicative_expression additive_expression2");
            skip();
            var right = parseMultiplicative_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.SUB, right);
            return parseAdditive_expression2(binary);
        }
        else{
            dump("additive_expression2 -> e");
            return leftExpression;
        }
    }

    private Expr parseMultiplicative_expression(){
        dump("multiplicative_expression -> prefix_expression multiplicative_expression2");
        var left = parsePrefix_expression();
        return parseMultiplicative_expression2(left);
    }

    private Expr parseMultiplicative_expression2(Expr leftExpression){
        if (peek() == OP_MUL){
            dump("multiplicative_expression2 -> * prefix_expression multiplicative_expression2");
            skip();
            var right = parsePrefix_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.MUL, right);
            return parseMultiplicative_expression2(binary);
        } else if (peek() == OP_DIV) {
            dump("multiplicative_expression2 -> / prefix_expression multiplicative_expression2");
            skip();
            var right = parsePrefix_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.DIV, right);
            return parseMultiplicative_expression2(binary);
        } else if (peek() == OP_MOD) {
            dump("multiplicative_expression2 -> % prefix_expression multiplicative_expression2");
            skip();
            var right = parsePrefix_expression();
            var binary = new Binary(new Position(leftExpression.position.start.line,
                    leftExpression.position.start.column, right.position.end.line, right.position.end.column),
                    leftExpression, Binary.Operator.MOD, right);
            return parseMultiplicative_expression2(binary);
        }
        else{
            dump("multiplicative_expression2 -> e");
            return leftExpression;
        }
    }

    private Expr parsePrefix_expression(){
        int startcol = getStartColumn(index);
        int startl = getStartLine(index);
        if (peek() == OP_ADD){
            dump("prefix_expression -> + prefix_expression");
            skip();
            var prefix = parsePrefix_expression();
            return new Unary(new Position(startl, startcol, prefix.position.end.line, prefix.position.end.column),
                    prefix, Unary.Operator.ADD);
        } else if (peek() == OP_SUB) {
            dump("prefix_expression -> - prefix_expression");
            skip();
            var prefix = parsePrefix_expression();
            return new Unary(new Position(startl, startcol, prefix.position.end.line, prefix.position.end.column),
                    prefix, Unary.Operator.SUB);
        } else if (peek() == OP_NOT) {
            dump("prefix_expression -> ! prefix_expression");
            skip();
            var prefix = parsePrefix_expression();
            return new Unary(new Position(startl, startcol,prefix.position.end.line, prefix.position.end.column),
                    prefix, Unary.Operator.NOT);
        }
        else {
            dump("prefix_expression -> postfix_expression");
            return parsePostfix_expression();
        }
    }

    private Expr parsePostfix_expression(){
        dump("postfix_expression -> atom_expression postfix_expression2");
        var left = parseAtom_expression();
        return parsePostfix_expression2(left);
    }

    private Expr parsePostfix_expression2(Expr leftExpression){
        if (peek() == OP_LBRACKET){
            dump("postfix_expression2 -> [ expression ] postfix_expression2");
            skip();
            var right = parseExpression();
            if (peek() == OP_RBRACKET){
                skip();
                var binary = new Binary(new Position(leftExpression.position.start.line,
                        leftExpression.position.start.column, getEndLine(index -1), getEndColumn(index - 1)),
                        leftExpression, Binary.Operator.ARR, right);
                return parsePostfix_expression2(binary);
            } else error();
            return leftExpression;
        }
        else{
            dump("postfix_expression2 -> e");
            return leftExpression;
        }
    }

    private Expr parseAtom_expression(){
        Position poz = new Position(getStartLine(index), getStartColumn(index), getEndLine(index),
                getEndColumn(index));
        int startl = getStartLine(index);
        int startcol = getStartColumn(index);
        if (peek() == C_LOGICAL){
            skip();
            dump("atom_expression -> log_constant");
            return new Literal(poz, getter(index-1).lexeme, Atom.Type.LOG);
        } else if (peek() == C_INTEGER) {
            skip();
            dump("atom_expression -> int_constant");
            return new Literal(poz, getter(index-1).lexeme, Atom.Type.INT);
        } else if (peek() == C_STRING) {
            skip();
            dump("atom_expression -> string_constant");
            return new Literal(poz, getter(index-1).lexeme, Atom.Type.STR);
        } else if (peek() == IDENTIFIER){
            var left = getter(index).lexeme;
            skip();
            dump("atom_expression -> identifier atom_expression2");
            return parseAtom_expression2(left, startl, startcol);
        } else if (peek() == OP_LBRACE) {
            dump("atom_expression -> { atom_expression3");
            skip();
            return parseAtom_expression3(startl, startcol);
        } else if (peek() == OP_LPARENT) {
            dump("atom_expression -> ( expressions )");
            skip();
            List<Expr> block = new ArrayList<>();
            var exprs = parseExpressions(block);
            if (peek() == OP_RPARENT){
                skip();
                return new Block(new Position(startl, startcol, getEndLine(index - 1),
                        getEndColumn(index - 1)), block);
            } else error();
        }
        return null;
    }

    private Expr parseAtom_expression3(int startl, int startcol){
        if (peek() == KW_IF){
            dump("atom_expression3 -> if expression then expression atom_expression4");
            skip();
            var expr =  parseExpression();
            if (peek() == KW_THEN){
                skip();
                var expr1 = parseExpression();
                return parseAtom_expression4(expr, expr1, startl, startcol);
            } else error();
        } else if (peek() == KW_WHILE) {
            dump("atom_expression3 -> while expression : expression }");
            skip();
            var expr = parseExpression();
            if (peek() == OP_COLON){
                skip();
                var expr1 = parseExpression();
                if (peek() ==  OP_RBRACE){
                    skip();
                    return new While(new Position(startl, startcol,
                            getEndLine(index - 1), getEndColumn(index - 1)), expr, expr1);
                } else error();
            } else error();

        } else if (peek() == KW_FOR) {
            dump("atom_expression3 -> for identifier = expression , expression , expression : expression }");
            skip();
            if (peek() == IDENTIFIER){
                Position poz1 = new Position(getStartLine(index), getStartColumn(index), getEndLine(index),
                        getEndColumn(index));
                Name name = new Name(poz1, getter(index).lexeme);
                skip();
                if (peek() == OP_ASSIGN){
                    skip();
                    var expr1 = parseExpression();
                    if (peek() == OP_COMMA){
                        skip();
                        var expr2 = parseExpression();
                        if (peek() == OP_COMMA){
                            skip();
                            var expr3 = parseExpression();
                            if (peek() == OP_COLON){
                                skip();
                                var expr4 = parseExpression();
                                if (peek() == OP_RBRACE){
                                    skip();
                                    return new For(new Position(startl, startcol,
                                            getEndLine(index - 1), getEndColumn(index - 1)),
                                            name, expr1, expr2, expr3, expr4);
                                } else error();
                            } else error();
                        } else error();
                    } else error();
                } else error();
            } else error();
        } else{
            dump("atom_expression3 -> expression = expression }");
            var expr1 = parseExpression();
            if (peek() == OP_ASSIGN){
                skip();
                var expr2 = parseExpression();
                if (peek() == OP_RBRACE){
                    skip();
                    return new Binary(new Position(startl, startcol,
                            getEndLine(index - 1), getEndColumn(index - 1)), expr1, Binary.Operator.ASSIGN, expr2);
                } else error();
            } else error();
        }
        return null;
    }

    private Expr parseAtom_expression4(Expr expr, Expr expr1, int startl, int startcol){
        if (peek() == OP_RBRACE){
            dump("atom_expression4 -> }");
            skip();
            return new IfThenElse(new Position(startl, startcol,
                    getEndLine(index - 1), getEndColumn(index - 1)), expr, expr1);
        }else if (peek() == KW_ELSE){
            dump("atom_expression4 -> else expression }");
            skip();
            var expr2 = parseExpression();
            if (peek() == OP_RBRACE){
                skip();
                return new IfThenElse(new Position(startl, startcol,
                        getEndLine(index - 1), getEndColumn(index - 1)), expr, expr1, expr2);
            } else error();
        }
        return null;
    }

    private Expr parseAtom_expression2(String name, int startl, int startcol){
        List<Expr> block = new ArrayList<>();
        Position poz = new Position(getStartLine(index), getStartColumn(index), getEndLine(index),
                getEndColumn(index));
        if (peek() == OP_LPARENT){
            dump("atom_expression2 -> ( expressions )");
            skip();
            var args = parseExpressions(block);
            if (peek() == OP_RPARENT){
                skip();
                return new Call(new Position(startl, startcol,
                        getEndLine(index - 1), getEndColumn(index - 1)), args, name);
            } else error(); return null;
        }
        else{
            dump("atom_expression2 -> e");
            return new Name(new Position(startl, startcol, getEndLine(index -1), getEndColumn(index - 1)), name);
        }
    }

    private List<Expr> parseExpressions(List<Expr> block){
        dump("expressions -> expression expressions2");
        var expr = parseExpression();
        block.add(expr);
        return parseExpressions2(block);
    }


    private List<Expr> parseExpressions2(List<Expr> block){
        Position poz = new Position(getStartLine(index), getStartColumn(index), getEndLine(index),
                getEndColumn(index));
        if (peek() == OP_COMMA){
            dump("expressions2 -> , expressions");
            skip();
            return parseExpressions(block);
        }
        else{
            dump("expressions2 -> e");
            return block;
        }
    }


    private List<FunDef.Parameter> parseParameters(List<FunDef.Parameter> p){
        dump("parameters -> parameter parameters2");
        var parameter = parseParameter();
        p.add(parameter);
        return parseParameters2(p);
    }

    private FunDef.Parameter parseParameter(){
        int startl = getStartLine(index);
        int startcol = getStartColumn(index);
        dump("parameter -> identifier : type");
        if (peek() == IDENTIFIER){
            String ime = getter(index).lexeme;
            skip();
            if (peek() == OP_COLON){
                skip();
                var type = parseType();
                return new FunDef.Parameter(new Position(startl, startcol, type.position.end.line,
                        type.position.end.column), ime, type);
            } else error();
        } else error();
        return null;
    }

    private List<FunDef.Parameter> parseParameters2(List<FunDef.Parameter> p){
        if (peek() == OP_COMMA){
            skip();
            dump("parameters2 -> , parameters");
            return parseParameters(p);
        }
        else{
            dump("parameters2 -> e");
            return p;
        }
    }

    private TypeDef parseType_definition(int startl, int startcol){
        dump("type_definition -> typ identifier : type");
        if (peek() == IDENTIFIER){
            String ime = getter(index).lexeme;
            skip();
            if (peek() == OP_COLON){
                skip();
                var type = parseType();
                return new TypeDef(new Position(startl,startcol,type.position.end.line, type.position.end.column),
                        ime, type);
            } else error();
        } else error();
        return null;
    }

    private Type parseType(){

        Position poz = new Position(getStartLine(index), getStartColumn(index), getEndLine(index), getEndColumn(index));

        String lex = getter(index).lexeme;
        if (peek() == IDENTIFIER){
            skip();
            dump("type -> identifier");
            return new TypeName(poz, lex);
        } else if (peek() == AT_LOGICAL) {
            skip();
            dump("type -> logical");
            return Atom.LOG(poz);
        } else if (peek() == AT_INTEGER) {
            skip();
            dump("type -> integer ");
            return Atom.INT(poz);
        } else if (peek() == AT_STRING) {
            skip();
            dump("type -> string");
            return Atom.STR(poz);
        } else if (peek() == KW_ARR) {
            int startArrLine = getStartLine(index);
            int startArrCol = getStartColumn(index);
            skip();
            if (peek() == OP_LBRACKET) {
                skip();
                if (peek() == C_INTEGER) {
                    int size = Integer.parseInt(getter(index).lexeme);
                    skip();
                    if (peek() == OP_RBRACKET) {
                        skip();
                        dump("type -> arr [ int_const ] type");
                        var type = parseType();
                        return new Array(new Position(startArrLine,startArrCol,type.position.end.line,
                                type.position.end.column), size, type);
                    } else error();
                } else error();
            } else error();
        }
        return null;
    }

    private List<Def> parseDefinitions2(List<Def> def){
        if (peek() == OP_SEMICOLON){
            skip();
            if (peek() == EOF){
                error();
            }
            dump("definitions2 -> ; definition definitions2");
            def.add(parseDefinition());
            return parseDefinitions2(def);
        }
        else{
            if (peek() != EOF && peek() != OP_RBRACE){
                if (symbols.get(index + 1).tokenType != EOF){
                    error();
                }
            }
        }
        dump("definitions2 -> e");
        return def;
    }

    /**
     * Izpiše produkcijo na izhodni tok.
     */
    private void dump(String production) {
        if (productionsOutputStream.isPresent()) {
            productionsOutputStream.get().println(production);
        }
    }
}
